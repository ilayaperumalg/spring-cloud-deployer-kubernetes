/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.cloud.deployer.spi.kubernetes.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelector;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretKeySelector;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.kubernetes.EntryPointStyle;
import org.springframework.cloud.deployer.spi.kubernetes.ImagePullPolicy;
import org.springframework.cloud.deployer.spi.kubernetes.KubernetesDeployerProperties;
import org.springframework.cloud.deployer.spi.scheduler.ScheduleRequest;
import org.springframework.cloud.deployer.spi.scheduler.kubernetes.KubernetesSchedulerProperties;
import org.springframework.cloud.deployer.spi.scheduler.kubernetes.RestartPolicy;
import org.springframework.cloud.deployer.spi.util.ByteSizeUtils;
import org.springframework.cloud.deployer.spi.util.CommandLineTokenizer;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import static java.lang.String.format;

public class DeploymentPropertiesResolver {

	private final Log logger = LogFactory.getLog(getClass().getName());

	public static final String STATEFUL_SET_IMAGE_NAME = "busybox";

	private String propertyPrefix;

	private KubernetesDeployerProperties properties;

	public DeploymentPropertiesResolver(String propertyPrefix, KubernetesDeployerProperties properties) {
		this.propertyPrefix = propertyPrefix;
		this.properties = properties;
	}

	public String getPropertyPrefix() {
		return this.propertyPrefix;
	}

	public List<Toleration> getTolerations(Map<String, String> kubernetesDeployerProperties) {
		List<Toleration> tolerations = new ArrayList<>();

		KubernetesDeployerProperties deployerProperties = bindProperties(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.tolerations", "tolerations" );

		deployerProperties.getTolerations().forEach(toleration -> tolerations.add(
				new Toleration(toleration.getEffect(), toleration.getKey(), toleration.getOperator(),
						toleration.getTolerationSeconds(), toleration.getValue())));

		this.properties.getTolerations().stream()
				.filter(toleration -> tolerations.stream()
						.noneMatch(existing -> existing.getKey().equals(toleration.getKey())))
				.collect(Collectors.toList())
				.forEach(toleration -> tolerations.add(new Toleration(toleration.getEffect(), toleration.getKey(),
						toleration.getOperator(), toleration.getTolerationSeconds(), toleration.getValue())));

		return tolerations;
	}

	/**
	 * Volume deployment properties are specified in YAML format:
	 *
	 * <code>
	 *     spring.cloud.deployer.kubernetes.volumes=[{name: testhostpath, hostPath: { path: '/test/override/hostPath' }},
	 *     	{name: 'testpvc', persistentVolumeClaim: { claimName: 'testClaim', readOnly: 'true' }},
	 *     	{name: 'testnfs', nfs: { server: '10.0.0.1:111', path: '/test/nfs' }}]
	 * </code>
	 *
	 * Volumes can be specified as deployer properties as well as app deployment properties.
	 * Deployment properties override deployer properties.
	 *
	 * @param request the {@link AppDeploymentRequest}
	 * @return the configured volumes
	 */
	public List<Volume> getVolumes(Map<String, String> kubernetesDeployerProperties) {
		List<Volume> volumes = new ArrayList<>();

		KubernetesDeployerProperties deployerProperties = bindProperties(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.volumes", "volumes");

		volumes.addAll(deployerProperties.getVolumes());

		// only add volumes that have not already been added, based on the volume's name
		// i.e. allow provided deployment volumes to override deployer defined volumes
		volumes.addAll(properties.getVolumes().stream()
				.filter(volume -> volumes.stream()
						.noneMatch(existingVolume -> existingVolume.getName().equals(volume.getName())))
				.collect(Collectors.toList()));

		return volumes;
	}

	/**
	 * Get the resource limits for the deployment request. A Pod can define its maximum needed resources by setting the
	 * limits and Kubernetes can provide more resources if any are free.
	 * <p>
	 * Falls back to the server properties if not present in the deployment request.
	 * <p>
	 *
	 * @param request The deployment properties.
	 * @return the resource limits to use
	 */
	public Map<String, Quantity> deduceResourceLimits(Map<String, String> kubernetesDeployerProperties) {
		String memory = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.limits.memory");

		if (StringUtils.isEmpty(memory)) {
			memory = properties.getLimits().getMemory();
		}

		String cpu = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.limits.cpu");

		if (StringUtils.isEmpty(cpu)) {
			cpu = properties.getLimits().getCpu();
		}

		Map<String,Quantity> limits = new HashMap<String,Quantity>();
		limits.put("memory", new Quantity(memory));
		limits.put("cpu", new Quantity(cpu));

		logger.debug("Using limits - cpu: " + cpu + " mem: " + memory);

		return limits;
	}

	/**
	 * Get the image pull policy for the deployment request. If it is not present use the server default. If an override
	 * for the deployment is present but not parseable, fall back to a default value.
	 *
	 * @param request The deployment request.
	 * @return The image pull policy to use for the container in the request.
	 */
	public ImagePullPolicy deduceImagePullPolicy(Map<String, String> kubernetesDeployerProperties) {
		String pullPolicyOverride = getPropertyValue(kubernetesDeployerProperties,
						this.propertyPrefix + "kubernetes.imagePullPolicy");

		ImagePullPolicy pullPolicy;
		if (pullPolicyOverride == null) {
			pullPolicy = properties.getImagePullPolicy();
		} else {
			pullPolicy = ImagePullPolicy.relaxedValueOf(pullPolicyOverride);
			if (pullPolicy == null) {
				logger.warn("Parsing of pull policy " + pullPolicyOverride + " failed, using default \"IfNotPresent\".");
				pullPolicy = ImagePullPolicy.IfNotPresent;
			}
		}
		logger.debug("Using imagePullPolicy " + pullPolicy);
		return pullPolicy;
	}

	/**
	 * Get the resource requests for the deployment request. Resource requests are guaranteed by the Kubernetes
	 * runtime.
	 * Falls back to the server properties if not present in the deployment request.
	 *
	 * @param request The deployment properties.
	 * @return the resource requests to use
	 */
	public Map<String, Quantity> deduceResourceRequests(Map<String, String> kubernetesDeployerProperties) {
		String memOverride = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.requests.memory");
		if (memOverride == null) {
			memOverride = properties.getRequests().getMemory();
		}


		String cpuOverride = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.requests.cpu");
		if (cpuOverride == null) {
			cpuOverride = properties.getRequests().getCpu();
		}

		logger.debug("Using requests - cpu: " + cpuOverride + " mem: " + memOverride);

		Map<String,Quantity> requests = new HashMap<String, Quantity>();
		if (memOverride != null) {
			requests.put("memory", new Quantity(memOverride));
		}
		if (cpuOverride != null) {
			requests.put("cpu", new Quantity(cpuOverride));
		}
		return requests;
	}

	public String getStatefulSetStorageClassName(Map<String, String> kubernetesDeployerProperties) {
		String storageClassName = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.statefulSet.volumeClaimTemplate.storageClassName");
		if (storageClassName == null && properties.getStatefulSet() != null && properties.getStatefulSet().getVolumeClaimTemplate() != null) {
			storageClassName = properties.getStatefulSet().getVolumeClaimTemplate().getStorageClassName();
		}
		return storageClassName;
	}

	public String getStatefulSetStorage(Map<String, String> kubernetesDeployerProperties) {
		String storage = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.statefulSet.volumeClaimTemplate.storage");;
		if (storage == null && properties.getStatefulSet() != null && properties.getStatefulSet().getVolumeClaimTemplate() != null) {
			storage = properties.getStatefulSet().getVolumeClaimTemplate().getStorage();
		}
		long storageAmount = ByteSizeUtils.parseToMebibytes(storage);
		return storageAmount + "Mi";
	}

	/**
	 * Get the hostNetwork setting for the deployment request.
	 *
	 * @param request The deployment request.
	 * @return Whether host networking is requested
	 */
	public boolean getHostNetwork(Map<String, String> kubernetesDeployerProperties) {
		String hostNetworkOverride = getPropertyValue(kubernetesDeployerProperties,
						this.propertyPrefix + "kubernetes.hostNetwork");
		boolean hostNetwork;
		if (StringUtils.isEmpty(hostNetworkOverride)) {
			hostNetwork = properties.isHostNetwork();
		}
		else {
			hostNetwork = Boolean.valueOf(hostNetworkOverride);
		}
		logger.debug("Using hostNetwork " + hostNetwork);
		return hostNetwork;
	}

	/**
	 * Get the nodeSelectors setting for the deployment request.
	 *
	 * @param deploymentProperties The deployment request deployment properties.
	 * @return map of nodeSelectors
	 */
	public Map<String, String> getNodeSelectors(Map<String, String> deploymentProperties) {
		Map<String, String> nodeSelectors = new HashMap<>();

		String nodeSelector = properties.getNodeSelector();

		String nodeSelectorDeploymentProperty = deploymentProperties
				.getOrDefault(KubernetesDeployerProperties.KUBERNETES_DEPLOYMENT_NODE_SELECTOR, "");

		boolean hasGlobalNodeSelector = StringUtils.hasText(properties.getNodeSelector());
		boolean hasDeployerPropertyNodeSelector = StringUtils.hasText(nodeSelectorDeploymentProperty);

		if ((hasGlobalNodeSelector && hasDeployerPropertyNodeSelector) ||
				(!hasGlobalNodeSelector && hasDeployerPropertyNodeSelector)) {
			nodeSelector = nodeSelectorDeploymentProperty;
		}

		if (StringUtils.hasText(nodeSelector)) {
			String[] nodeSelectorPairs = nodeSelector.split(",");
			for (String nodeSelectorPair : nodeSelectorPairs) {
				String[] selector = nodeSelectorPair.split(":");
				Assert.isTrue(selector.length == 2, format("Invalid nodeSelector value: '{}'", nodeSelectorPair));
				nodeSelectors.put(selector[0].trim(), selector[1].trim());
			}
		}

		return nodeSelectors;
	}

	public String getImagePullSecret(Map<String, String> kubernetesDeployerProperties) {
		String imagePullSecret = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.imagePullSecret", "");

		if(StringUtils.isEmpty(imagePullSecret)) {
			imagePullSecret = this.properties.getImagePullSecret();
		}

		return imagePullSecret;
	}

	public String getDeploymentServiceAccountName(Map<String, String> kubernetesDeployerProperties) {
		String deploymentServiceAccountName = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.deploymentServiceAccountName");

		if (StringUtils.isEmpty(deploymentServiceAccountName)) {
			deploymentServiceAccountName = properties.getDeploymentServiceAccountName();
		}

		return deploymentServiceAccountName;
	}

	public void setPodSecurityContext(Map<String, String> kubernetesDeployerProperties, PodSpecBuilder podSpecBuilder) {
		PodSecurityContext podSecurityContext = null;

		KubernetesDeployerProperties deployerProperties = bindProperties(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.podSecurityContext", "podSecurityContext");

		if (deployerProperties.getPodSecurityContext() != null) {
			podSecurityContext = new PodSecurityContextBuilder()
					.withRunAsUser(deployerProperties.getPodSecurityContext().getRunAsUser())
					.withFsGroup(deployerProperties.getPodSecurityContext().getFsGroup())
					.build();
		}
		else {
			String runAsUser = getPropertyValue(kubernetesDeployerProperties,
					this.propertyPrefix + "kubernetes.podSecurityContext.runAsUser");
			String fsGroup = getPropertyValue(kubernetesDeployerProperties,
					this.propertyPrefix + "kubernetes.podSecurityContext.fsGroup");
			if (!StringUtils.isEmpty(runAsUser) && !StringUtils.isEmpty(fsGroup)) {
				podSecurityContext = new PodSecurityContextBuilder()
						.withRunAsUser(Long.valueOf(runAsUser))
						.withFsGroup(Long.valueOf(fsGroup))
						.build();
			}
			else if (this.properties.getPodSecurityContext() != null) {
				podSecurityContext = new PodSecurityContextBuilder()
						.withRunAsUser(this.properties.getPodSecurityContext().getRunAsUser())
						.withFsGroup(this.properties.getPodSecurityContext().getFsGroup())
						.build();
			}
		}

		if (podSecurityContext != null) {
			podSpecBuilder.withSecurityContext(podSecurityContext);
		}
	}

	public void setAffinityRules(Map<String, String> kubernetesDeployerProperties, PodSpecBuilder podSpecBuilder) {
		Affinity affinity = new Affinity();

		String nodeAffinityPropertyKey = this.propertyPrefix + "kubernetes.affinity.nodeAffinity";
		String podAffinityPropertyKey = this.propertyPrefix + "kubernetes.affinity.podAffinity";
		String podAntiAffinityPropertyKey = this.propertyPrefix + "kubernetes.affinity.podAntiAffinity";

		String nodeAffinityValue = getPropertyValue(kubernetesDeployerProperties,
				nodeAffinityPropertyKey);
		String podAffinityValue = getPropertyValue(kubernetesDeployerProperties,
				podAffinityPropertyKey);
		String podAntiAffinityValue = getPropertyValue(kubernetesDeployerProperties,
				podAntiAffinityPropertyKey);

		if (properties.getNodeAffinity() != null && !StringUtils.hasText(nodeAffinityValue)) {
			affinity.setNodeAffinity(new AffinityBuilder()
					.withNodeAffinity(properties.getNodeAffinity())
					.buildNodeAffinity());
		} else if (StringUtils.hasText(nodeAffinityValue)) {
			KubernetesDeployerProperties nodeAffinityProperties = bindProperties(kubernetesDeployerProperties,
					nodeAffinityPropertyKey, "nodeAffinity");

			affinity.setNodeAffinity(new AffinityBuilder()
					.withNodeAffinity(nodeAffinityProperties.getNodeAffinity())
					.buildNodeAffinity());
		}

		if (properties.getPodAffinity() != null && !StringUtils.hasText(podAffinityValue)) {
			affinity.setPodAffinity(new AffinityBuilder()
					.withPodAffinity(properties.getPodAffinity())
					.buildPodAffinity());
		} else if (StringUtils.hasText(podAffinityValue)) {
			KubernetesDeployerProperties podAffinityProperties = bindProperties(kubernetesDeployerProperties,
					podAffinityPropertyKey, "podAffinity");

			affinity.setPodAffinity(new AffinityBuilder()
					.withPodAffinity(podAffinityProperties.getPodAffinity())
					.buildPodAffinity());
		}

		if (properties.getPodAntiAffinity() != null && !StringUtils.hasText(podAntiAffinityValue)) {
			affinity.setPodAntiAffinity(new AffinityBuilder()
					.withPodAntiAffinity(properties.getPodAntiAffinity())
					.buildPodAntiAffinity());
		} else if (StringUtils.hasText(podAntiAffinityValue)) {
			KubernetesDeployerProperties podAntiAffinityProperties = bindProperties(kubernetesDeployerProperties,
					podAntiAffinityPropertyKey, "podAntiAffinity");

			affinity.setPodAntiAffinity(new AffinityBuilder()
					.withPodAntiAffinity(podAntiAffinityProperties.getPodAntiAffinity())
					.buildPodAntiAffinity());
		}

		// Make sure there is at least some rule.
		if (affinity.getNodeAffinity() != null
				|| affinity.getPodAffinity() != null
				|| affinity.getPodAntiAffinity() != null) {
			podSpecBuilder.withAffinity(affinity);
		}
	}

	public void setInitContainer(Map<String, String> kubernetesDeployerProperties, PodSpecBuilder podSpec) {
		KubernetesDeployerProperties deployerProperties = bindProperties(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.initContainer", "initContainer");
		if (deployerProperties.getInitContainer() == null) {
			String containerName = getPropertyValue(kubernetesDeployerProperties, this.propertyPrefix + "kubernetes.initContainer.containerName");
			String imageName = getPropertyValue(kubernetesDeployerProperties, this.propertyPrefix + "kubernetes.initContainer.imageName");
			String commands = getPropertyValue(kubernetesDeployerProperties, this.propertyPrefix + "kubernetes.initContainer.commands");
			if (!StringUtils.isEmpty(containerName) && !StringUtils.isEmpty(imageName)) {
				Container container = new ContainerBuilder()
						.withName(containerName)
						.withImage(imageName)
						.withCommand(commands)
						.build();
				podSpec.addToInitContainers(container);
			}
		}
		else {
			KubernetesDeployerProperties.InitContainer initContainer = deployerProperties.getInitContainer();
			if (initContainer != null) {
				Container container = new ContainerBuilder()
						.withName(initContainer.getContainerName())
						.withImage(initContainer.getImageName())
						.withCommand(initContainer.getCommands())
						.build();
				podSpec.addToInitContainers(container);
			}
		}
	}

	public Map<String, String> getPodAnnotations(Map<String, String> kubernetesDeployerProperties) {
		String annotationsValue = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.podAnnotations", "");
		if (StringUtils.isEmpty(annotationsValue)) {
			annotationsValue = properties.getPodAnnotations();
		}
		return PropertyParserUtils.getAnnotations(annotationsValue);
	}

	public Map<String, String> getServiceAnnotations(Map<String, String> kubernetesDeployerProperties) {
		String annotationsProperty = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.serviceAnnotations", "");

		if (StringUtils.isEmpty(annotationsProperty)) {
			annotationsProperty = this.properties.getServiceAnnotations();
		}

		return PropertyParserUtils.getAnnotations(annotationsProperty);
	}

	public Map<String, String> getDeploymentLabels(Map<String, String> kubernetesDeployerProperties) {
		Map<String, String> labels = new HashMap<>();

		String deploymentLabels = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.deploymentLabels", "");

		if (StringUtils.hasText(deploymentLabels)) {
			String[] deploymentLabel = deploymentLabels.split(",");

			for (String label : deploymentLabel) {
				String[] labelPair = label.split(":");
				Assert.isTrue(labelPair.length == 2,
						format("Invalid label format, expected 'labelKey:labelValue', got: '%s'", labelPair));
				labels.put(labelPair[0].trim(), labelPair[1].trim());
			}
		}

		return labels;
	}

	public RestartPolicy getRestartPolicy(Map<String, String> kubernetesDeployerProperties) {
		String restartPolicy = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.restartPolicy", "");

		if (StringUtils.hasText(restartPolicy)) {
			return RestartPolicy.valueOf(restartPolicy);
		}

		return ((KubernetesSchedulerProperties) this.properties).getRestartPolicy();
	}

	public String getTaskServiceAccountName(Map<String, String> kubernetesDeployerProperties) {
		String taskServiceAccountName = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "kubernetes.taskServiceAccountName", "");

		if (StringUtils.hasText(taskServiceAccountName)) {
			return taskServiceAccountName;
		}

		return ((KubernetesSchedulerProperties) this.properties).getTaskServiceAccountName();
	}

	public static String getPropertyValue(Map<String, String> properties, String propertyName) {
		return getPropertyValue(properties, propertyName, null);
	}

	public static String getPropertyValue(Map<String, String> properties, String propertyName,
			String defaultValue) {
		RelaxedNames relaxedNames = new RelaxedNames(propertyName);
		for (Iterator<String> itr = relaxedNames.iterator(); itr.hasNext();) {
			String relaxedName = itr.next();
			if (properties.containsKey(relaxedName)) {
				return properties.get(relaxedName);
			}
		}
		return defaultValue;
	}

	/**
	 * Binds the YAML formatted value of a deployment property to a {@link KubernetesDeployerProperties} instance.
	 *
	 * @param kubernetesDeployerProperties the map of Kubernetes deployer properties
	 * @param propertyKey the property key to obtain the value to bind for
	 * @param yamlLabel the label representing the field to bind to
	 * @return a {@link KubernetesDeployerProperties} with the bound property data
	 */
	public static KubernetesDeployerProperties bindProperties(Map<String, String> kubernetesDeployerProperties,
			String propertyKey, String yamlLabel) {
		String deploymentPropertyValue = kubernetesDeployerProperties.getOrDefault(propertyKey, "");

		KubernetesDeployerProperties deployerProperties = new KubernetesDeployerProperties();

		if (!StringUtils.isEmpty(deploymentPropertyValue)) {
			try {
				YamlPropertiesFactoryBean properties = new YamlPropertiesFactoryBean();
				String tmpYaml = "{ " + yamlLabel + ": " + deploymentPropertyValue + " }";
				properties.setResources(new ByteArrayResource(tmpYaml.getBytes()));
				Properties yaml = properties.getObject();
				MapConfigurationPropertySource source = new MapConfigurationPropertySource(yaml);
				deployerProperties = new Binder(source)
						.bind("", Bindable.of(KubernetesDeployerProperties.class)).get();
			} catch (Exception e) {
				throw new IllegalArgumentException(
						String.format("Invalid binding property '%s'", deploymentPropertyValue), e);
			}
		}

		return deployerProperties;
	}

	public String getStatefulSetInitContainerImageName(Map<String, String> kubernetesDeployerProperties) {
		String statefulSetInitContainerImageName = getPropertyValue(kubernetesDeployerProperties,
				this.propertyPrefix + "statefulSetInitContainerImageName", "");

		if (StringUtils.hasText(statefulSetInitContainerImageName)) {
			return statefulSetInitContainerImageName;
		}

		statefulSetInitContainerImageName = this.properties.getStatefulSetInitContainerImageName();

		if (StringUtils.hasText(statefulSetInitContainerImageName)) {
			return statefulSetInitContainerImageName;
		}

		return STATEFUL_SET_IMAGE_NAME;
	}

	public Map<String, String> getJobAnnotations(AppDeploymentRequest request) {
		String annotationsProperty = PropertyParserUtils.getDeploymentPropertyValue(request.getDeploymentProperties(),
				"spring.cloud.deployer.kubernetes.jobAnnotations", "");

		if (StringUtils.isEmpty(annotationsProperty)) {
			annotationsProperty = this.properties.getJobAnnotations();
		}

		return PropertyParserUtils.getAnnotations(annotationsProperty);
	}

	/**
	 * Volume mount deployment properties are specified in YAML format:
	 * <p>
	 * <code>
	 * spring.cloud.deployer.kubernetes.volumeMounts=[{name: 'testhostpath', mountPath: '/test/hostPath'},
	 * {name: 'testpvc', mountPath: '/test/pvc'}, {name: 'testnfs', mountPath: '/test/nfs'}]
	 * </code>
	 * <p>
	 * Volume mounts can be specified as deployer properties as well as app deployment properties.
	 * Deployment properties override deployer properties.
	 *
	 * @param request the {@link AppDeploymentRequest}
	 * @return the configured volume mounts
	 */
	public List<VolumeMount> getVolumeMounts(Map<String, String> deploymentProperties) {
		List<VolumeMount> volumeMounts = new ArrayList<>();
		String volumeMountDeploymentProperty = getPropertyValue(deploymentProperties,
				this.propertyPrefix + "kubernetes.volumeMounts");
		if (!StringUtils.isEmpty(volumeMountDeploymentProperty)) {
			try {
				YamlPropertiesFactoryBean properties = new YamlPropertiesFactoryBean();
				String tmpYaml = "{ volume-mounts: " + volumeMountDeploymentProperty + " }";
				properties.setResources(new ByteArrayResource(tmpYaml.getBytes()));
				Properties yaml = properties.getObject();
				MapConfigurationPropertySource source = new MapConfigurationPropertySource(yaml);
				KubernetesDeployerProperties deployerProperties = new Binder(source)
						.bind("", Bindable.of(KubernetesDeployerProperties.class)).get();
				volumeMounts.addAll(deployerProperties.getVolumeMounts());
			} catch (Exception e) {
				throw new IllegalArgumentException(
						String.format("Invalid volume mount '%s'", volumeMountDeploymentProperty), e);
			}
		}
		// only add volume mounts that have not already been added, based on the volume mount's name
		// i.e. allow provided deployment volume mounts to override deployer defined volume mounts
		volumeMounts.addAll(this.properties.getVolumeMounts().stream().filter(volumeMount -> volumeMounts.stream()
				.noneMatch(existingVolumeMount -> existingVolumeMount.getName().equals(volumeMount.getName())))
				.collect(Collectors.toList()));

		return volumeMounts;
	}

	/**
	 * The list represents a single command with many arguments.
	 *
	 * @param request AppDeploymentRequest - used to gather application overridden
	 *                container command
	 * @return a list of strings that represents the command and any arguments for that command
	 */
	public List<String> getContainerCommand(Map<String, String> deploymentProperties) {
		String containerCommand = getPropertyValue(deploymentProperties,
				this.propertyPrefix + "kubernetes.containerCommand", "");
		return new CommandLineTokenizer(containerCommand).getArgs();
	}

	/**
	 * @param request AppDeploymentRequest - used to gather additional container ports
	 * @return a list of Integers to add to the container
	 */
	public List<Integer> getContainerPorts(Map<String, String> deploymentProperties) {
		List<Integer> containerPortList = new ArrayList<>();
		String containerPorts = getPropertyValue(deploymentProperties,
				this.propertyPrefix + "kubernetes.containerPorts", null);
		if (containerPorts != null) {
			String[] containerPortSplit = containerPorts.split(",");
			for (String containerPort : containerPortSplit) {
				logger.trace("Adding container ports from AppDeploymentRequest: " + containerPort);
				Integer port = Integer.parseInt(containerPort.trim());
				containerPortList.add(port);
			}
		}
		return containerPortList;
	}

	/**
	 * @param request AppDeploymentRequest - used to gather application specific
	 *                environment variables
	 * @return a List of EnvVar objects for app specific environment settings
	 */
	public Map<String, String> getAppEnvironmentVariables(Map<String, String> deploymentProperties) {
		Map<String, String> appEnvVarMap = new HashMap<>();
		String appEnvVar = getPropertyValue(deploymentProperties,
				this.propertyPrefix + "kubernetes.environmentVariables", null);
		if (appEnvVar != null) {
			String[] appEnvVars = new NestedCommaDelimitedVariableParser().parse(appEnvVar);
			for (String envVar : appEnvVars) {
				logger.trace("Adding environment variable from AppDeploymentRequest: " + envVar);
				String[] strings = envVar.split("=", 2);
				Assert.isTrue(strings.length == 2, "Invalid environment variable declared: " + envVar);
				appEnvVarMap.put(strings[0], strings[1]);
			}
		}
		return appEnvVarMap;
	}

	static class NestedCommaDelimitedVariableParser {
		static final String REGEX = "(\\w+='.+?'),?";

		static final Pattern pattern = Pattern.compile(REGEX);

		String[] parse(String value) {

			String[] vars = value.replaceAll(pattern.pattern(), "").split(",");

			Matcher m = pattern.matcher(value);
			while (m.find()) {
				vars = Arrays.copyOf(vars, vars.length + 1);
				vars[vars.length - 1] = m.group(1).replaceAll("'", "");
			}
			return vars;
		}
	}

	public EntryPointStyle determineEntryPointStyle(Map<String, String> deploymentProperties) {
		EntryPointStyle entryPointStyle = null;
		String deployerPropertyValue = getPropertyValue(deploymentProperties,
				this.propertyPrefix + "kubernetes.entryPointStyle", null);
		if (deployerPropertyValue != null) {
			try {
				entryPointStyle = EntryPointStyle.valueOf(deployerPropertyValue.toLowerCase());
			}
			catch (IllegalArgumentException ignore) {
			}
		}
		if (entryPointStyle == null) {
			entryPointStyle = this.properties.getEntryPointStyle();
		}
		return entryPointStyle;
	}

	public List<EnvVar> getConfigMapKeyRefs(Map<String, String> deploymentProperties) {
		List<EnvVar> configMapKeyRefs = new ArrayList<>();
		KubernetesDeployerProperties deployerProperties = bindProperties(deploymentProperties,
				this.propertyPrefix + "kubernetes.configMapKeyRefs", "configMapKeyRefs");

		deployerProperties.getConfigMapKeyRefs().forEach(configMapKeyRef ->
				configMapKeyRefs.add(buildConfigMapKeyRefEnvVar(configMapKeyRef)));

		properties.getConfigMapKeyRefs().stream()
				.filter(configMapKeyRef -> configMapKeyRefs.stream()
						.noneMatch(existing -> existing.getName().equals(configMapKeyRef.getEnvVarName())))
				.collect(Collectors.toList())
				.forEach(configMapKeyRef -> configMapKeyRefs.add(buildConfigMapKeyRefEnvVar(configMapKeyRef)));

		return configMapKeyRefs;
	}

	private EnvVar buildConfigMapKeyRefEnvVar(KubernetesDeployerProperties.ConfigMapKeyRef configMapKeyRef) {
		ConfigMapKeySelector configMapKeySelector = new ConfigMapKeySelector();

		EnvVarSource envVarSource = new EnvVarSource();
		envVarSource.setConfigMapKeyRef(configMapKeySelector);

		EnvVar configMapKeyEnvRefVar = new EnvVar();
		configMapKeyEnvRefVar.setValueFrom(envVarSource);
		configMapKeySelector.setName(configMapKeyRef.getConfigMapName());
		configMapKeySelector.setKey(configMapKeyRef.getDataKey());
		configMapKeyEnvRefVar.setName(configMapKeyRef.getEnvVarName());

		return configMapKeyEnvRefVar;
	}

	public List<EnvVar> getSecretKeyRefs(Map<String, String> deploymentProperties) {
		List<EnvVar> secretKeyRefs = new ArrayList<>();

		KubernetesDeployerProperties deployerProperties = bindProperties(deploymentProperties,
				this.propertyPrefix + "kubernetes.secretKeyRefs", "secretKeyRefs" );

		deployerProperties.getSecretKeyRefs().forEach(secretKeyRef ->
				secretKeyRefs.add(buildSecretKeyRefEnvVar(secretKeyRef)));

		properties.getSecretKeyRefs().stream()
				.filter(secretKeyRef -> secretKeyRefs.stream()
						.noneMatch(existing -> existing.getName().equals(secretKeyRef.getEnvVarName())))
				.collect(Collectors.toList())
				.forEach(secretKeyRef -> secretKeyRefs.add(buildSecretKeyRefEnvVar(secretKeyRef)));

		return secretKeyRefs;
	}

	private EnvVar buildSecretKeyRefEnvVar(KubernetesDeployerProperties.SecretKeyRef secretKeyRef) {
		SecretKeySelector secretKeySelector = new SecretKeySelector();

		EnvVarSource envVarSource = new EnvVarSource();
		envVarSource.setSecretKeyRef(secretKeySelector);

		EnvVar secretKeyEnvRefVar = new EnvVar();
		secretKeyEnvRefVar.setValueFrom(envVarSource);
		secretKeySelector.setName(secretKeyRef.getSecretName());
		secretKeySelector.setKey(secretKeyRef.getDataKey());
		secretKeyEnvRefVar.setName(secretKeyRef.getEnvVarName());

		return secretKeyEnvRefVar;
	}
}
