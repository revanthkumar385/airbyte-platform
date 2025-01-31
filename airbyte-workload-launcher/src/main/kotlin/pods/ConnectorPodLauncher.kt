package io.airbyte.workload.launcher.pods

import io.airbyte.commons.workers.config.WorkerConfigs
import io.airbyte.config.ResourceRequirements
import io.airbyte.config.TolerationPOJO
import io.airbyte.workers.process.KubeContainerInfo
import io.airbyte.workers.process.KubePodInfo
import io.airbyte.workers.process.KubePodProcess
import io.airbyte.workers.sync.OrchestratorConstants
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerPort
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.LocalObjectReference
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Toleration
import io.fabric8.kubernetes.api.model.TolerationBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.micronaut.context.annotation.Value
import jakarta.inject.Named
import jakarta.inject.Singleton

// TODO: make sure these beans are wired up
// TODO: Use this in KubePodClient to launch
@Singleton
class ConnectorPodLauncher(
  private val kubernetesClient: KubernetesClient,
  @Named("checkWorkerConfigs") private val checkWorkerConfigs: WorkerConfigs,
  @Named("checkOrchestratorReqs") private val checkReqs: ResourceRequirements,
  @Named("checkEnvVars") private val envVars: List<EnvVar>,
  @Named("orchestratorEnvVars") private val sidecarEnvVars: List<EnvVar>,
  @Named("checkContainerPorts") private val containerPorts: List<ContainerPort>,
  @Named("sidecarKubeContainerInfo") private val sidecarContainerInfo: KubeContainerInfo,
  @Value("\${airbyte.worker.job.kube.serviceAccount}") private val serviceAccount: String?,
) {
  fun create(
    allLabels: Map<String, String>,
    nodeSelectors: Map<String, String>,
    kubePodInfo: KubePodInfo,
    annotations: Map<String, String>,
    extraEnv: Map<String, String>,
  ): Pod {
    val volumes: MutableList<Volume> = ArrayList()
    val volumeMounts: MutableList<VolumeMount> = ArrayList()

    volumes.add(
      VolumeBuilder()
        .withName("airbyte-config")
        .withNewEmptyDir()
        .withMedium("Memory")
        .endEmptyDir()
        .build(),
    )

    volumeMounts.add(
      VolumeMountBuilder()
        .withName("airbyte-config")
        .withMountPath(KubePodProcess.CONFIG_DIR)
        .build(),
    )

    val pullSecrets: List<LocalObjectReference> =
      checkWorkerConfigs.jobImagePullSecrets
        .map { imagePullSecret -> LocalObjectReference(imagePullSecret) }
        .toList()

    val init: Container = buildInitContainer(volumeMounts)
    val main: Container = buildMainContainer(volumeMounts, kubePodInfo.mainContainerInfo)
    val sidecar: Container = buildSidecarContainer(volumeMounts, extraEnv)

    val podToCreate =
      PodBuilder()
        .withApiVersion("v1")
        .withNewMetadata()
        .withName(kubePodInfo.name)
        .withLabels<String, String>(allLabels)
        .withAnnotations<String, String>(annotations)
        .endMetadata()
        .withNewSpec()
//      .withSchedulerName("binpacking-scheduler") // TODO (after spike): inject this or use FF
        .withServiceAccount(serviceAccount)
        .withAutomountServiceAccountToken(true)
        .withRestartPolicy("Never")
        .withContainers(sidecar, main)
        .withInitContainers(init)
        .withVolumes(volumes)
        .withNodeSelector<String, String>(nodeSelectors)
        .withTolerations(buildPodTolerations(checkWorkerConfigs.workerKubeTolerations))
        .withImagePullSecrets(pullSecrets) // An empty list or an empty LocalObjectReference turns this into a no-op setting.
        .endSpec()
        .build()

    return kubernetesClient.pods()
      .inNamespace(kubePodInfo.namespace)
      .resource(podToCreate)
      .serverSideApply()
  }

  // TODO (after spike): inject this
  private fun buildPodTolerations(tolerations: List<TolerationPOJO>?): List<Toleration>? {
    if (tolerations.isNullOrEmpty()) {
      return null
    }
    return tolerations
      .map { t ->
        TolerationBuilder()
          .withKey(t.key)
          .withEffect(t.effect)
          .withOperator(t.operator)
          .withValue(t.value)
          .build()
      }
      .toList()
  }

  private fun buildInitContainer(volumeMounts: List<VolumeMount>): Container {
    return ContainerBuilder()
      .withName(KubePodProcess.INIT_CONTAINER_NAME)
      .withImage("busybox:1.35")
      .withWorkingDir(KubePodProcess.CONFIG_DIR)
      .withCommand(
        listOf(
          "sh",
          "-c",
          String.format(
            """
            i=0
            until [ ${'$'}i -gt 60 ]
            do
              echo "${'$'}i - waiting for config file transfer to complete..."
              # check if the upload-complete file exists, if so exit without error
              if [ -f "%s/%s" ]; then
                exit 0
              fi
              i=${'$'}((i+1))
              sleep 1
            done
            echo "config files did not transfer in time"
            # no upload-complete file was created in time, exit with error
            exit 1
            """.trimIndent(),
            KubePodProcess.CONFIG_DIR,
            KubePodProcess.SUCCESS_FILE_NAME,
          ),
        ),
      )
      .withResources(KubePodProcess.getResourceRequirementsBuilder(checkReqs).build()) // // TODO (after spike): inject this
      .withVolumeMounts(volumeMounts)
      .build()
  }

  private fun buildMainContainer(
    volumeMounts: List<VolumeMount>,
    containerInfo: KubeContainerInfo,
  ): Container {
    /**
     * TODO:
     * - create output file(s)
     * - run executable and pipe to file
     * - write finished file with exit code
     * - Override entry point and other stuff that main.sh is doing?
     * - Make this script generic?
     */
    val mainCommand =
      """
      pwd
      
      eval "${'$'}AIRBYTE_ENTRYPOINT check --config ${KubePodProcess.CONFIG_DIR}/connectionConfiguration.json" > ${KubePodProcess.CONFIG_DIR}/${OrchestratorConstants.CHECK_JOB_OUTPUT_FILENAME}
      
      cat ${KubePodProcess.CONFIG_DIR}/${OrchestratorConstants.CHECK_JOB_OUTPUT_FILENAME}
      
      echo $? > ${KubePodProcess.CONFIG_DIR}/${OrchestratorConstants.EXIT_CODE_FILE}
      """.trimIndent()

    return ContainerBuilder()
      .withName(KubePodProcess.MAIN_CONTAINER_NAME)
//      .withPorts(containerPorts)
      .withImage(containerInfo.image)
      .withImagePullPolicy(checkWorkerConfigs.jobImagePullPolicy) // TODO: this should be properly set on the kubePodInfo
      .withCommand("sh", "-c", mainCommand)
      .withEnv(envVars)
      .withWorkingDir(KubePodProcess.CONFIG_DIR)
      .withVolumeMounts(volumeMounts)
      .withResources(KubePodProcess.getResourceRequirementsBuilder(checkReqs).build())
      .build()
  }

  fun buildSidecarContainer(
    volumeMounts: List<VolumeMount>,
    extraEnv: Map<String, String>,
  ): Container {
    val extraKubeEnv = extraEnv.map { (k, v) -> EnvVar(k, v, null) }

    return ContainerBuilder()
      .withName(KubePodProcess.SIDECAR_CONTAINER_NAME)
      // Do we need ports?
      .withPorts(containerPorts)
      .withImage(sidecarContainerInfo.image)
      .withImagePullPolicy(sidecarContainerInfo.pullPolicy)
//      .withCommand("sh", "-c", "docker run")
      .withWorkingDir(KubePodProcess.CONFIG_DIR)
      .withEnv(sidecarEnvVars + extraKubeEnv)
      .withVolumeMounts(volumeMounts)
      .withResources(KubePodProcess.getResourceRequirementsBuilder(checkReqs).build())
      .build()
  }
}
