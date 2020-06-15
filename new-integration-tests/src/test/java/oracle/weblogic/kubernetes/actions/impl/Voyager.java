// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.actions.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1HTTPIngressPath;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1HTTPIngressRuleValue;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1Ingress;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1IngressBackend;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1IngressList;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1IngressRule;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1IngressSpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import oracle.weblogic.kubernetes.actions.impl.primitive.Helm;
import oracle.weblogic.kubernetes.actions.impl.primitive.HelmParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;

import static oracle.weblogic.kubernetes.extensions.LoggedTest.logger;

/**
 * Utility class for Voyager ingress controller.
 */
public class Voyager {
  /**
   * Install Voyager Helm chart.
   *
   * @param params the parameters to Helm install command such as release name, namespace, repo url or chart dir,
   *               chart name and chart values
   * @return true on success, false otherwise
   */
  public static boolean install(VoyagerParams params) {
    return Helm.install(params.getHelmParams(), params.getValues());
  }

  /**
   * Upgrade Voyager Helm release.
   *
   * @param params the parameters to Helm upgrade command such as release name, namespace and chart values to override
   * @return true on success, false otherwise
   */
  public static boolean upgrade(VoyagerParams params) {
    return Helm.upgrade(params.getHelmParams(), params.getValues());
  }

  /**
   * Uninstall Voyager Helm release.
   *
   * @param params the parameters to Helm uninstall command such as release name and namespace
   * @return true on success, false otherwise
   */
  public static boolean uninstall(HelmParams params) {
    return Helm.uninstall(params);
  }

  /**
   * Create an ingress for the WebLogic domain with domainUid in the specified domain namespace.
   * The ingress host is set to 'domainUid.clusterName.test'.
   *
   * @param ingressName name of the ingress to be created
   * @param domainNamespace the WebLogic domain namespace in which the ingress will be created
   * @param domainUid the WebLogic domainUid which is backend to the ingress
   * @param clusterNameMsPortMap the map with key as cluster name and value as managed server port of the cluster
   * @return list of ingress hosts or null if got ApiException when calling Kubernetes client API to create ingress
   */
  public static List<String> createIngress(String ingressName,
                                           String domainNamespace,
                                           String domainUid,
                                           Map<String, Integer> clusterNameMsPortMap) {

    final String ingressApiVersion = "extensions/v1beta1";
    final String ingressKind = "Ingress";
    final String ingressType = "NodePort";
    final String ingressAffinity = "cookie";
    final String ingressClass = "voyager";

    // set the annotations for Voyager
    HashMap<String, String> annotation = new HashMap<>();
    annotation.put("ingress.appscode.com/type", ingressType);
    annotation.put("ingress.appscode.com/affinity", ingressAffinity);
    annotation.put("kubernetes.io/ingress.class", ingressClass);

    List<String> ingressHostList = new ArrayList<>();
    ArrayList<ExtensionsV1beta1IngressRule> ingressRules = new ArrayList<>();
    clusterNameMsPortMap.forEach((clusterName, managedServerPort) -> {
      // set the http ingress paths
      ExtensionsV1beta1HTTPIngressPath httpIngressPath = new ExtensionsV1beta1HTTPIngressPath()
          .path(null)
          .backend(new ExtensionsV1beta1IngressBackend()
              .serviceName(domainUid + "-cluster-" + clusterName.toLowerCase().replace("_", "-"))
              .servicePort(new IntOrString(managedServerPort))
          );
      ArrayList<ExtensionsV1beta1HTTPIngressPath> httpIngressPaths = new ArrayList<>();
      httpIngressPaths.add(httpIngressPath);

      // set the ingress rule
      String ingressHost = domainUid + "." + clusterName + ".org";
      ExtensionsV1beta1IngressRule ingressRule = new ExtensionsV1beta1IngressRule()
          .host(ingressHost)
          .http(new ExtensionsV1beta1HTTPIngressRuleValue()
              .paths(httpIngressPaths));

      ingressRules.add(ingressRule);
      ingressHostList.add(ingressHost);
    });

    // set the ingress
    ExtensionsV1beta1Ingress ingress = new ExtensionsV1beta1Ingress()
        .apiVersion(ingressApiVersion)
        .kind(ingressKind)
        .metadata(new V1ObjectMeta()
            .name(ingressName)
            .namespace(domainNamespace)
            .annotations(annotation))
        .spec(new ExtensionsV1beta1IngressSpec()
            .rules(ingressRules));

    // create the ingress
    try {
      Kubernetes.createIngress(domainNamespace, ingress);
    } catch (ApiException apex) {
      logger.severe("got ApiException while calling createIngress: {0}", apex.getResponseBody());
      return null;
    }
    return ingressHostList;
  }

  /**
   * List all of the ingresses in the specified namespace.
   *
   * @param namespace the namespace to which the ingresses belong
   * @return a list of ingress names in the namespace
   * @throws ApiException if Kubernetes client API call fails
   */
  public static List<String> listIngresses(String namespace) throws ApiException {

    List<String> ingressNames = new ArrayList<>();
    ExtensionsV1beta1IngressList ingressList = Kubernetes.listNamespacedIngresses(namespace);
    List<ExtensionsV1beta1Ingress> listOfIngress = ingressList.getItems();

    listOfIngress.forEach(ingress -> {
      if (ingress.getMetadata() != null) {
        ingressNames.add(ingress.getMetadata().getName());
      }
    });

    return ingressNames;
  }
}