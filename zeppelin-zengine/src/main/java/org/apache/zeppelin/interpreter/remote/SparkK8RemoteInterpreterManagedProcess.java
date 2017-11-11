/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.interpreter.remote;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.exec.*;
import org.apache.commons.exec.environment.EnvironmentUtils;
import org.apache.zeppelin.interpreter.thrift.RemoteInterpreterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class manages start / stop of Spark remote interpreter process on a Kubernetes cluster.
 * After Spark Driver started by spark-submit is in Running state, creates a Kubernetes service
 * to connect to RemoteInterpreterServer running inside Spark Driver.
 */
public class SparkK8RemoteInterpreterManagedProcess extends RemoteInterpreterProcess
        implements ExecuteResultHandler {

  private static final Logger logger = LoggerFactory.getLogger(
      SparkK8RemoteInterpreterManagedProcess.class);

  public static final String SPARK_APP_SELECTOR = "spark-app-selector";
  public static final String DRIVER_SERVICE_NAME_SUFFIX = "-ri-svc";
  public static final String KUBERNETES_NAMESPACE = "default";
  public static final String DRIVER_POD_NAME_PREFIX = "zri-";
  public static final String INTERPRETER_PROCESS_ID = "interpreter-processId";

  protected final String interpreterRunner;
  protected final String portRange;
  private DefaultExecutor executor;
  protected ProcessLogOutputStream processOutput;
  private ExecuteWatchdog watchdog;
  protected AtomicBoolean running = new AtomicBoolean(false);
  protected String host = "localhost";
  protected int port = -1;
  protected final String interpreterDir;
  protected final String localRepoDir;
  protected final String interpreterGroupName;

  protected Map<String, String> env;

  /**
   * Default url for Kubernetes inside of an Kubernetes cluster.
   */
  private static String K8_URL = "https://kubernetes:443";
  private KubernetesClient kubernetesClient;
  private String driverPodName;
  private Service driverService;
  private String interpreterSettingId;
  private String processLabelId;
  private String connectionStatus = "Driver pod not found.";

  public SparkK8RemoteInterpreterManagedProcess(String intpRunner,
                                                String portRange,
                                                String intpDir,
                                                String localRepoDir,
                                                Map<String, String> env,
                                                int connectTimeout,
                                                String interpreterGroupName,
                                                String interpreterSettingId) {

    super(connectTimeout);
    this.interpreterRunner = intpRunner;
    this.portRange = portRange;
    this.env = env;
    this.interpreterDir = intpDir;
    this.localRepoDir = localRepoDir;
    this.interpreterGroupName = interpreterGroupName;
    this.processLabelId = generatePodLabelId(interpreterSettingId);
    this.interpreterSettingId = formatId(interpreterSettingId, 50);
    this.port = 30000;
  }

  /**
   * Id for spark submit must be formatted to contain only alfanumeric chars.
   * @param str
   * @param maxLength
   * @return
   */
  private String formatId(String str, int maxLength) {
    str = str.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
    if (str.length() > maxLength) {
      str = str.substring(0, maxLength - 1);
    }
    return str;
  }

  private String generatePodLabelId(String interpreterGroupId ) {
    return formatId(interpreterGroupId + "_" + System.currentTimeMillis(), 64);
  }

  @Override
  public void start(String userName, Boolean isUserImpersonate) {
    CommandLine cmdLine = CommandLine.parse(interpreterRunner);
    cmdLine.addArgument("-d", false);
    cmdLine.addArgument(interpreterDir, false);
    cmdLine.addArgument("-p", false);
    cmdLine.addArgument(Integer.toString(port), false);
    if (isUserImpersonate && !userName.equals("anonymous")) {
      cmdLine.addArgument("-u", false);
      cmdLine.addArgument(userName, false);
    }
    cmdLine.addArgument("-l", false);
    cmdLine.addArgument(localRepoDir, false);

    if (interpreterSettingId != null) {
      cmdLine.addArgument("-g", false);
      cmdLine.addArgument(interpreterSettingId, false);
    }
    if (processLabelId != null) {
      cmdLine.addArgument("-i", false);
      cmdLine.addArgument(processLabelId, false);
    }


    ByteArrayOutputStream cmdOut = executeCommand(cmdLine);

    long startTime = System.currentTimeMillis();
    while (System.currentTimeMillis() - startTime < getConnectTimeout()) {
      host = obtainEndpointHost();
      try {
        if (host != null && RemoteInterpreterUtils.checkIfRemoteEndpointAccessible(host, port)) {
          running.set(true);
          break;
        } else {
          try {
            logger.info("wait ... {}", connectionStatus);
            Thread.sleep(500);
          } catch (InterruptedException e) {
            logger.error("Exception in RemoteInterpreterProcess while synchronized reference " +
                    "Thread.sleep", e);
          }
        }
      } catch (Exception e) {
        if (logger.isDebugEnabled()) {
          logger.debug("Remote interpreter not yet accessible at {}:{}", host, port);
        }
      }
    }

    if (!running.get()) {
      stop();
      throw new RuntimeException("Unable to start SparkK8RemoteInterpreterManagedProcess: " +
              connectionStatus);
    }
  }

  protected String obtainEndpointHost() {
    String hostName = null;
    // try to obtain endpoint url from Spark driver
    try (final KubernetesClient client = getKubernetesClient()) {
      Pod driverPod = getSparkDriverPod(client, DRIVER_POD_NAME_PREFIX + interpreterSettingId);
      if (driverPod != null) {
        driverPodName = driverPod.getMetadata().getName();
        logger.debug("Driver pod name: " + driverPodName);
        Service driverService  = getOrCreateEndpointService(client, driverPod);
        if (driverService != null) {
          logger.info("ClusterIP {}", driverService.getSpec().getClusterIP());
          hostName = driverService.getSpec().getClusterIP();
          connectionStatus = "Driver service is available at " + host + ":" + port;
        }
      }
    } catch (KubernetesClientException e) {
      logger.error(e.getMessage(), e);
    }
    return hostName;
  }

  private KubernetesClient getKubernetesClient() {
    if (kubernetesClient == null) {
      Config config = new ConfigBuilder().withMasterUrl(K8_URL).build();
      logger.info("Connect to Kubernetes cluster at: {}", K8_URL);
      kubernetesClient = new DefaultKubernetesClient(config);
    }
    return kubernetesClient;
  }

  private Pod getSparkDriverPod(KubernetesClient client, String podLabel)
      throws KubernetesClientException {

    List<Pod> podList = client.pods().inNamespace(KUBERNETES_NAMESPACE)
     .withLabel(INTERPRETER_PROCESS_ID, processLabelId).list().getItems();
    if (podList.size() >= 1) {
      for (Pod remoteServerPod : podList) {
        String podName = remoteServerPod.getMetadata().getName();
        if (podName != null && podName.startsWith(podLabel)) {
          connectionStatus = "Driver pod found. Status: " + remoteServerPod.getStatus().getPhase();
          logger.debug(connectionStatus);
          if (remoteServerPod.getStatus().getPhase().equalsIgnoreCase("running")) {
            return remoteServerPod;
          }
        }
      }
    } else {
      logger.debug("Pod not found!");
    }

    return null;
  }

  private Service getEndpointService(KubernetesClient client, String serviceName)
      throws KubernetesClientException {
    logger.debug("Check if RemoteInterpreterServer service {} exists", serviceName);
    return client.services().inNamespace(KUBERNETES_NAMESPACE).withName(serviceName).get();
  }

  private Service getOrCreateEndpointService(KubernetesClient client, Pod driverPod)
      throws KubernetesClientException {
    String serviceName = driverPodName + DRIVER_SERVICE_NAME_SUFFIX;
    driverService = getEndpointService(client, serviceName);

    // create endpoint service for RemoteInterpreterServer
    if (driverService == null) {
      Map<String, String> labels = driverPod.getMetadata().getLabels();
      String label = labels.get(SPARK_APP_SELECTOR);
      logger.info("Create RemoteInterpreterServer service for spark-app-selector: {}", label);
      driverService = new ServiceBuilder().withNewMetadata()
              .withName(serviceName).endMetadata()
              .withNewSpec().addNewPort().withProtocol("TCP")
              .withPort(getPort()).withNewTargetPort(getPort()).endPort()
              .addToSelector(SPARK_APP_SELECTOR, label)
              .withType("ClusterIP")
              .endSpec().build();
      driverService = client.services().inNamespace(KUBERNETES_NAMESPACE).create(driverService);
    }

    return driverService;
  }

  private void deleteEndpointService(KubernetesClient client)
      throws KubernetesClientException {
    boolean result = client.services().inNamespace(KUBERNETES_NAMESPACE).delete(driverService);
    logger.info("Delete RemoteInterpreterServer service {} : {}",
      driverService.getMetadata().getName(), result);
  }

  protected void stopEndPoint() {
    if (driverPodName != null) {
      try (KubernetesClient client = getKubernetesClient()) {
        deleteEndpointService(client);
        client.close();
        kubernetesClient = null;
      } catch (KubernetesClientException e) {
        logger.error(e.getMessage(), e);
      }
    }
  }

  @Override
  public String getHost() {
    return host;
  }

  @Override
  public int getPort() {
    return port;
  }

  protected ByteArrayOutputStream executeCommand(CommandLine cmdLine) {

    executor = new DefaultExecutor();

    ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
    processOutput = new ProcessLogOutputStream(logger);
    processOutput.setOutputStream(cmdOut);

    executor.setStreamHandler(new PumpStreamHandler(processOutput));
    watchdog = new ExecuteWatchdog(ExecuteWatchdog.INFINITE_TIMEOUT);
    executor.setWatchdog(watchdog);

    try {
      Map procEnv = EnvironmentUtils.getProcEnvironment();
      procEnv.putAll(env);

      logger.info("Run interpreter process {}", cmdLine);
      executor.execute(cmdLine, procEnv, this);
    } catch (IOException e) {
      running.set(false);
      throw new RuntimeException(e);
    }

    return cmdOut;
  }

  public void stop() {
    // shutdown EventPoller first.
    getRemoteInterpreterEventPoller().shutdown();
    stopEndPoint();
    if (isRunning()) {
      logger.info("kill interpreter process");
      try {
        callRemoteFunction(new RemoteFunction<Void>() {
          @Override
          public Void call(RemoteInterpreterService.Client client) throws Exception {
            client.shutdown();
            return null;
          }
        });
      } catch (Exception e) {
        logger.warn("ignore the exception when shutting down");
      }
    }

    executor = null;
    watchdog.destroyProcess();
    watchdog = null;
    running.set(false);
    logger.info("Remote process terminated");
  }

  public void onProcessComplete(int exitValue) {
    logger.info("Interpreter process exited {}", exitValue);
    running.set(false);

  }

  public void onProcessFailed(ExecuteException e) {
    logger.info("Interpreter process failed {}", e);
    running.set(false);
  }

  @VisibleForTesting
  public Map<String, String> getEnv() {
    return env;
  }

  @VisibleForTesting
  public String getLocalRepoDir() {
    return localRepoDir;
  }

  @VisibleForTesting
  public String getInterpreterDir() {
    return interpreterDir;
  }

  @VisibleForTesting
  public String getInterpreterGroupName() {
    return interpreterGroupName;
  }

  @VisibleForTesting
  public String getInterpreterRunner() {
    return interpreterRunner;
  }

  public boolean isRunning() {
    return running.get();
  }

  /**
   * ProcessLogOutputStream
   */
  protected static class ProcessLogOutputStream extends LogOutputStream {

    private Logger logger;
    OutputStream out;

    public ProcessLogOutputStream(Logger logger) {
      this.logger = logger;
    }

    @Override
    protected void processLine(String s, int i) {
      this.logger.debug(s);
    }

    @Override
    public void write(byte [] b) throws IOException {
      super.write(b);

      if (out != null) {
        synchronized (this) {
          if (out != null) {
            out.write(b);
          }
        }
      }
    }

    @Override
    public void write(byte [] b, int offset, int len) throws IOException {
      super.write(b, offset, len);

      if (out != null) {
        synchronized (this) {
          if (out != null) {
            out.write(b, offset, len);
          }
        }
      }
    }

    public void setOutputStream(OutputStream out) {
      synchronized (this) {
        this.out = out;
      }
    }
  }

}
