// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import io.kubernetes.client.openapi.models.V1SecretReference;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.actions.impl.primitive.HelmParams;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.annotations.tags.MustNotRunInParallel;
import oracle.weblogic.kubernetes.annotations.tags.Slow;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.BuildApplication;
import oracle.weblogic.kubernetes.utils.DeployUtil;
import oracle.weblogic.kubernetes.utils.ExecResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.OCR_EMAIL;
import static oracle.weblogic.kubernetes.TestConstants.OCR_PASSWORD;
import static oracle.weblogic.kubernetes.TestConstants.OCR_REGISTRY;
import static oracle.weblogic.kubernetes.TestConstants.OCR_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.OCR_USERNAME;
import static oracle.weblogic.kubernetes.TestConstants.REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.APP_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.actions.TestActions.uninstallTraefik;
import static oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes.listSecrets;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReady;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDockerRegistrySecret;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createTraefikIngressForDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.installAndVerifyTraefik;
import static oracle.weblogic.kubernetes.utils.TestUtils.getNextFreePort;
import static oracle.weblogic.kubernetes.utils.TestUtils.verifyClusterMemberCommunication;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests related to introspectVersion attribute.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Verify the introspectVersion runs the introspector")
@IntegrationTest
public class ItTraefikLoadBalancer {

  private static String opNamespace = null;
  private static String domainNamespace = null;
  private static String traefikNamespace = null;

  private static int nodeportshttp;
  
  private static HelmParams traefikHelmParams = null;

  private static final String IMAGE = MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG;

  private final String wlSecretName = "weblogic-credentials";

  private static Path clusterViewAppPath;
  private static LoggingFacade logger = null;

  /**
   * Assigns unique namespaces for operator and domains.Installs operator.
   *
   * @param namespaces injected by JUnit
   */
  @BeforeAll
  public static void initAll(@Namespaces(4) List<String> namespaces) {
    logger = getLogger();

    logger.info("Assign a unique namespace for operator");
    opNamespace = namespaces.get(0);

    logger.info("Assign a unique namespace for WebLogic domain domain1");
    domainNamespace = namespaces.get(1);

    logger.info("Assign a unique namespace for Traefik");
    traefikNamespace = namespaces.get(2);

    // install operator and verify its running in ready state
    installAndVerifyOperator(opNamespace, domainNamespace);

    // get a free web and websecure ports for Traefik
    nodeportshttp = getNextFreePort(30380, 30405);
    int nodeportshttps = getNextFreePort(31443, 31743);

    // install and verify Traefik
    traefikHelmParams = installAndVerifyTraefik(traefikNamespace, nodeportshttp, nodeportshttps);

    // build the clusterview application
    Path distDir = BuildApplication.buildApplication(Paths.get(APP_DIR, "clusterview"), null, null,
        "dist", domainNamespace);
    assertTrue(Paths.get(distDir.toString(),
        "clusterview.war").toFile().exists(),
        "Application archive is not available");
    clusterViewAppPath = Paths.get(distDir.toString(), "clusterview.war");

  }

  @Test
  @Order(1)
  @DisplayName("Create model in image domain")
  @Slow
  @MustNotRunInParallel
  public void testTraefikLoadbalancer() {

    // Create the repo secret to pull the image
    assertDoesNotThrow(() -> createDockerRegistrySecret(domainNamespace),
        String.format("createSecret failed for %s", REPO_SECRET_NAME));

    // create WebLogic domain credential secret
    createSecretWithUsernamePassword(wlSecretName, domainNamespace,
        ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecret";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
        encryptionSecretName,
        domainNamespace,
        "weblogicenc",
        "weblogicenc"),
        String.format("createSecret failed for %s", encryptionSecretName));

    int replicaCount = 2;
    String managedServerNameBase = "managed-server";

    for (int n = 1; n <= replicaCount; n++) {

      String domainUid = "domain" + n;
      // admin/managed server name here should match with model yaml in MII_BASIC_WDT_MODEL_FILE
      String adminServerPodName = domainUid + "-admin-server";
      String managedServerPrefix = domainUid + "-managed-server";

      // create the domain object
      Domain domain = createDomainResource(domainUid,
          domainNamespace,
          REPO_SECRET_NAME,
          encryptionSecretName,
          replicaCount,
          IMAGE);

      // create model in image domain
      logger.info("Creating model in image domain {0} in namespace {1} using docker image {2}",
          domainUid, domainNamespace, MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG);
      createDomainAndVerify(domain, domainNamespace);

      // check admin server pod is ready
      logger.info("Wait for admin server pod {0} to be ready in namespace {1}",
          adminServerPodName, domainNamespace);
      checkPodReady(adminServerPodName, domainUid, domainNamespace);

      // check managed server pods are ready
      for (int i = 1; i <= replicaCount; i++) {
        logger.info("Wait for managed server pod {0} to be ready in namespace {1}",
            managedServerPrefix + i, domainNamespace);
        checkPodReady(managedServerPrefix + i, domainUid, domainNamespace);
      }

      logger.info("Check admin service {0} is created in namespace {1}",
          adminServerPodName, domainNamespace);
      checkServiceExists(adminServerPodName, domainNamespace);

      // check managed server services created
      for (int i = 1; i <= replicaCount; i++) {
        logger.info("Check managed server service {0} is created in namespace {1}",
            managedServerPrefix + i, domainNamespace);
        checkServiceExists(managedServerPrefix + i, domainNamespace);
      }

      //create ingress resource - rules for loadbalancing
      Map<String, Integer> clusterNameMsPortMap = new HashMap<>();
      clusterNameMsPortMap.put("cluster-1", 8001);
      logger.info("Creating ingress for domain {0} in namespace {1}", domainUid, domainNamespace);
      createTraefikIngressForDomainAndVerify(domainUid, domainNamespace, 0, clusterNameMsPortMap, true);

      logger.info("Getting node port for default channel");
      int serviceNodePort = assertDoesNotThrow(()
          -> getServiceNodePort(domainNamespace, adminServerPodName + "-external", "default"),
          "Getting admin server node port failed");

      ExecResult result = DeployUtil.deployUsingRest(K8S_NODEPORT_HOST,
          String.valueOf(serviceNodePort),
          ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT,
          "cluster-1", clusterViewAppPath, null, "clusterview");
      assertNotNull(result, "Application deployment failed");
      logger.info("Application deployment returned {0}", result.toString());
      assertEquals("202", result.stdout(), "Deployment didn't return HTTP status code 202");

      //access application in managed servers through NGINX load balancer
      logger.info("Accessing the clusterview app through traefik load balancer");
      String curlRequest = String.format("curl --silent --show-error --noproxy '*' "
          + "-H 'host: %s' http://%s:%s/clusterview/ClusterViewServlet",
          domainUid + "." + domainNamespace + "." + "cluster-1" + ".test", K8S_NODEPORT_HOST, nodeportshttp);
      List<String> managedServers = new ArrayList<>();
      for (int i = 1; i <= replicaCount; i++) {
        managedServers.add(managedServerNameBase + i);
      }
      assertThat(verifyClusterMemberCommunication(curlRequest, managedServers, 20))
          .as("Verify all managed servers can see each other")
          .withFailMessage("managed servers cannot see other")
          .isTrue();
    }
  }

  private Domain createDomainResource(String domainUid, String domNamespace,
      String repoSecretName, String encryptionSecretName, int replicaCount,
      String miiImage) {
    // create the domain CR
    return new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(repoSecretName))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(wlSecretName)
                .namespace(domNamespace))
            .includeServerOutInPodLog(true)
            .serverStartPolicy("IF_NEEDED")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.StdoutDebugEnabled=false"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom ")))
            .adminServer(new AdminServer()
                .serverStartState("RUNNING")
                .adminService(new AdminService()
                    .addChannelsItem(new Channel()
                        .channelName("default")
                        .nodePort(0))))
            .addClustersItem(new Cluster()
                .clusterName("cluster-1")
                .replicas(replicaCount)
                .serverStartState("RUNNING"))
            .configuration(new Configuration()
                .model(new Model()
                    .domainType("WLS")
                    .runtimeEncryptionSecret(encryptionSecretName))
                .introspectorJobActiveDeadlineSeconds(300L)));

  }

  /**
   * Create secret for docker credentials.
   *
   * @param namespace name of the namespace in which to create secret
   */
  private static void createOCRRepoSecret(String namespace) {
    boolean secretExists = false;
    V1SecretList listSecrets = listSecrets(namespace);
    if (null != listSecrets) {
      for (V1Secret item : listSecrets.getItems()) {
        if (item.getMetadata().getName().equals(OCR_SECRET_NAME)) {
          secretExists = true;
          break;
        }
      }
    }
    if (!secretExists) {
      createDockerRegistrySecret(OCR_USERNAME, OCR_PASSWORD,
          OCR_EMAIL, OCR_REGISTRY, OCR_SECRET_NAME, namespace);
    }
  }

  /**
   * Uninstall Traefik. The cleanup framework does not uninstall Traefik release. Do it here for now.
   */
  @AfterAll
  public void tearDownAll() {
    // uninstall Traefik loadbalancer
    if (traefikHelmParams != null) {
      assertThat(uninstallTraefik(traefikHelmParams))
          .as("Test uninstallNginx returns true")
          .withFailMessage("uninstallNginx() did not return true")
          .isTrue();
    }
  }

}
