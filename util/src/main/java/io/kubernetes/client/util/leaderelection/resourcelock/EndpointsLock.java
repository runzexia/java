package io.kubernetes.client.util.leaderelection.resourcelock;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1Endpoints;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.util.leaderelection.LeaderElectionRecord;
import io.kubernetes.client.util.leaderelection.Lock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EndpointsLock implements Lock {

  private static final Logger log = LoggerFactory.getLogger(EndpointsLock.class);

  String LeaderElectionRecordAnnotationKey = "control-plane.alpha.kubernetes.io/leader";

  // Namespace and name describes the endpoint object
  // that the LeaderElector will attempt to lead.
  private final String namespace;
  private final String name;
  private final String identity;

  private CoreV1Api coreV1Client;

  private AtomicReference<V1Endpoints> endpointsRefer = new AtomicReference<>(null);

  public EndpointsLock(String namespace, String name, String identity) {
    this(namespace, name, identity, Configuration.getDefaultApiClient());
  }

  public EndpointsLock(String namespace, String name, String identity, ApiClient apiClient) {
    this.namespace = namespace;
    this.name = name;
    this.identity = identity;
    this.coreV1Client = new CoreV1Api(apiClient);
  }

  @Override
  public LeaderElectionRecord get() throws ApiException {
    V1Endpoints endpoints = coreV1Client.readNamespacedEndpoints(name, namespace, null, null, null);
    Map<String, String> annotations = endpoints.getMetadata().getAnnotations();
    if (annotations == null || annotations.isEmpty()) {
      endpoints.getMetadata().setAnnotations(new HashMap<>());
    }

    String recordRawStringContent =
        endpoints.getMetadata().getAnnotations().get(LeaderElectionRecordAnnotationKey);
    if (StringUtils.isEmpty(recordRawStringContent)) {
      return new LeaderElectionRecord();
    }
    LeaderElectionRecord record =
        coreV1Client
            .getApiClient()
            .getJSON()
            .deserialize(recordRawStringContent, LeaderElectionRecord.class);
    endpointsRefer.set(endpoints);
    return record;
  }

  @Override
  public boolean create(LeaderElectionRecord record) {
    try {
      V1Endpoints endpoints = new V1Endpoints();
      V1ObjectMeta objectMeta = new V1ObjectMeta();
      objectMeta.setName(name);
      objectMeta.setNamespace(namespace);
      Map<String, String> annotations = new HashMap<>();
      annotations.put(
          LeaderElectionRecordAnnotationKey,
          coreV1Client.getApiClient().getJSON().serialize(record));
      objectMeta.setAnnotations(annotations);
      endpoints.setMetadata(objectMeta);
      V1Endpoints createdendpoints =
          coreV1Client.createNamespacedEndpoints(namespace, endpoints, null, null, null);
      endpointsRefer.set(createdendpoints);
      return true;
    } catch (Throwable t) {
      log.error("failed to create leader election record as {}", t.getMessage());
      return false;
    }
  }

  @Override
  public boolean update(LeaderElectionRecord record) {
    try {
      V1Endpoints endpoints = endpointsRefer.get();
      endpoints
          .getMetadata()
          .putAnnotationsItem(
              LeaderElectionRecordAnnotationKey,
              coreV1Client.getApiClient().getJSON().serialize(record));
      V1Endpoints replacedEndpoints =
          coreV1Client.replaceNamespacedEndpoints(name, namespace, endpoints, null, null, null);
      endpointsRefer.set(replacedEndpoints);
      return true;
    } catch (Throwable t) {
      log.error("failed to update leader election record as {}", t.getMessage());
      return false;
    }
  }

  @Override
  public String identity() {
    return identity;
  }

  @Override
  public String describe() {
    return namespace + "/" + name;
  }
}
