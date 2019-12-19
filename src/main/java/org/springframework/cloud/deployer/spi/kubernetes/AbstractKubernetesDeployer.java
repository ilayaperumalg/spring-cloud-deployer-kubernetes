/*
 * Copyright 2015-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.kubernetes;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.kubernetes.support.DeploymentPropertiesResolver;
import org.springframework.cloud.deployer.spi.scheduler.ScheduleRequest;
import org.springframework.cloud.deployer.spi.util.RuntimeVersionUtils;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

/**
 * Abstract base class for a deployer that targets Kubernetes.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author Mark Fisher
 * @author Donovan Muller
 * @author David Turanski
 * @author Chris Schaefer
 * @author Enrique Medina Montenegro
 * @author Ilayaperumal Gopinathan
 */
public class AbstractKubernetesDeployer {

	protected static final String SPRING_DEPLOYMENT_KEY = "spring-deployment-id";
	protected static final String SPRING_GROUP_KEY = "spring-group-id";
	protected static final String SPRING_APP_KEY = "spring-app-id";
	protected static final String SPRING_MARKER_KEY = "role";
	protected static final String SPRING_MARKER_VALUE = "spring-app";

	protected final Log logger = LogFactory.getLog(getClass().getName());

	protected ContainerFactory containerFactory;

	protected KubernetesClient client;

	protected KubernetesDeployerProperties properties = new KubernetesDeployerProperties();

	protected DeploymentPropertiesResolver deploymentPropertiesResolver;

	/**
	 * Create the RuntimeEnvironmentInfo.
	 *
	 * @param spiClass the SPI interface class
	 * @param implementationClass the SPI implementation class
	 * @return the Kubernetes runtime environment info
	 */
	protected RuntimeEnvironmentInfo createRuntimeEnvironmentInfo(Class spiClass, Class implementationClass) {
		return new RuntimeEnvironmentInfo.Builder()
				.spiClass(spiClass)
				.implementationName(implementationClass.getSimpleName())
				.implementationVersion(RuntimeVersionUtils.getVersion(implementationClass))
				.platformType("Kubernetes")
				.platformApiVersion(client.getApiVersion())
				.platformClientVersion(RuntimeVersionUtils.getVersion(client.getClass()))
				.platformHostVersion("unknown")
				.addPlatformSpecificInfo("master-url", String.valueOf(client.getMasterUrl()))
				.addPlatformSpecificInfo("namespace", client.getNamespace())
				.build();
	}

	/**
	 * Creates a map of labels for a given ID. This will allow Kubernetes services
	 * to "select" the right ReplicationControllers.
	 *
	 * @param appId the application id
	 * @param request The {@link AppDeploymentRequest}
	 * @return the built id map of labels
	 */
	protected Map<String, String> createIdMap(String appId, AppDeploymentRequest request) {
		Map<String, String> map = new HashMap<>();
		map.put(SPRING_APP_KEY, appId);
		String groupId = request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY);
		if (groupId != null) {
			map.put(SPRING_GROUP_KEY, groupId);
		}
		map.put(SPRING_DEPLOYMENT_KEY, appId);
		return map;
	}

	protected AppStatus buildAppStatus(String id, PodList podList, ServiceList services) {
		AppStatus.Builder statusBuilder = AppStatus.of(id);
		Service service = null;
		if (podList != null && podList.getItems() != null) {
			for (Pod pod : podList.getItems()) {
				String deploymentKey = pod.getMetadata().getLabels().get(SPRING_DEPLOYMENT_KEY);
				for (Service svc : services.getItems()) {
					if (svc.getMetadata().getName().equals(deploymentKey)) {
						service = svc;
						break;
					}
				}
				//find the container with the correct env var
				for(Container container : pod.getSpec().getContainers()) {
					if(container.getEnv().stream().anyMatch(envVar -> "SPRING_CLOUD_APPLICATION_GUID".equals(envVar.getName()))) {
						//find container status for this container
						Optional<ContainerStatus> containerStatusOptional =
							pod.getStatus().getContainerStatuses()
							   .stream().filter(containerStatus -> container.getName().equals(containerStatus.getName()))
							   .findFirst();

						if(containerStatusOptional.isPresent()) {
							statusBuilder.with(new KubernetesAppInstanceStatus(pod, service, properties, containerStatusOptional.get()));
						}

						break;
					}
				}
			}
		}
		return statusBuilder.build();
	}

	public void logPossibleDownloadResourceMessage(Resource resource) {
		if (logger.isInfoEnabled()) {
			logger.info("Preparing to run a container from  " + resource
					+ ". This may take some time if the image must be downloaded from a remote container registry.");
		}
	}

	protected PodSpec createPodSpec(String appId, AppDeploymentRequest appDeploymentRequest, Integer port,
			boolean neverRestart) {

		Map<String, String>  deploymentProperties = (appDeploymentRequest instanceof ScheduleRequest) ?
				((ScheduleRequest) appDeploymentRequest).getSchedulerProperties() : appDeploymentRequest.getDeploymentProperties();

		PodSpecBuilder podSpec = new PodSpecBuilder();

		String imagePullSecret = this.deploymentPropertiesResolver.getImagePullSecret(deploymentProperties);

		if (imagePullSecret != null) {
			podSpec.addNewImagePullSecret(imagePullSecret);
		}

		boolean hostNetwork = this.deploymentPropertiesResolver.getHostNetwork(deploymentProperties);

		ContainerConfiguration containerConfiguration = new ContainerConfiguration(appId, appDeploymentRequest)
				.withProbeCredentialsSecret(getProbeCredentialsSecret(deploymentProperties,
						this.deploymentPropertiesResolver.getPropertyPrefix()))
				.withExternalPort(port)
				.withHostNetwork(hostNetwork);

		Container container = containerFactory.create(containerConfiguration);

		// add memory and cpu resource limits
		ResourceRequirements req = new ResourceRequirements();
		req.setLimits(this.deploymentPropertiesResolver.deduceResourceLimits(deploymentProperties));
		req.setRequests(this.deploymentPropertiesResolver.deduceResourceRequests(deploymentProperties));
		container.setResources(req);
		ImagePullPolicy pullPolicy = this.deploymentPropertiesResolver.deduceImagePullPolicy(deploymentProperties);
		container.setImagePullPolicy(pullPolicy.name());

		Map<String, String> nodeSelectors = this.deploymentPropertiesResolver.getNodeSelectors(deploymentProperties);
		if (nodeSelectors.size() > 0) {
			podSpec.withNodeSelector(nodeSelectors);
		}

		podSpec.withTolerations(this.deploymentPropertiesResolver.getTolerations(deploymentProperties));

		// only add volumes with corresponding volume mounts
		podSpec.withVolumes(this.deploymentPropertiesResolver.getVolumes(deploymentProperties).stream()
				.filter(volume -> container.getVolumeMounts().stream()
						.anyMatch(volumeMount -> volumeMount.getName().equals(volume.getName())))
				.collect(Collectors.toList()));

		if (hostNetwork) {
			podSpec.withHostNetwork(true);
		}
		podSpec.addToContainers(container);

		if (neverRestart){
			podSpec.withRestartPolicy("Never");
		}

		String deploymentServiceAcccountName = this.deploymentPropertiesResolver.getDeploymentServiceAccountName(deploymentProperties);

		if (deploymentServiceAcccountName != null) {
			podSpec.withServiceAccountName(deploymentServiceAcccountName);
		}

		this.deploymentPropertiesResolver.setPodSecurityContext(deploymentProperties, podSpec);

		this.deploymentPropertiesResolver.setAffinityRules(deploymentProperties, podSpec);

		this.deploymentPropertiesResolver.setInitContainer(deploymentProperties, podSpec);

		return podSpec.build();
	}


	public Secret getProbeCredentialsSecret(Map<String, String> kubernetesDeployerProperties, String propertyPrefix) {
		String secretName = DeploymentPropertiesResolver.getPropertyValue(kubernetesDeployerProperties,
				propertyPrefix + "kubernetes.probeCredentialsSecret");

		if (!StringUtils.isEmpty(secretName)) {
			return this.client.secrets().withName(secretName).get();
		}

		return null;
	}

}
