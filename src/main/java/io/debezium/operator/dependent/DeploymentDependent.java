/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.operator.dependent;

import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.debezium.operator.DebeziumServer;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

public class DeploymentDependent extends CRUDKubernetesDependentResource<Deployment, DebeziumServer> {

    public static final String DEFAULT_IMAGE = "quay.io/debezium/server";
    public static final String CONFIG_VOLUME_NAME = "ds-config";
    public static final String CONFIG_FILE_NAME = "application.properties";
    public static final String CONFIG_FILE_PATH = "/debezium/conf/" + CONFIG_FILE_NAME;

    public static final String DATA_VOLUME_NAME = "ds-data";
    public static final String DATA_DIR_PATH = "/debezium/data";
    public static final int DEFAULT_HTTP_PORT = 8080;

    @ConfigProperty(name = "debezium.image", defaultValue = DEFAULT_IMAGE)
    String defaultImage;

    public DeploymentDependent() {
        super(Deployment.class);
    }

    @Override
    protected Deployment desired(DebeziumServer primary, Context<DebeziumServer> context) {
        var name = primary.getMetadata().getName();
        var image = primary.getSpec().getImage();
        var taggedImage = (image != null) ? image : defaultImage + ":" + primary.getSpec().getVersion();
        var labels = Map.of("app", name);
        var dataVolume = desiredDataVolume(primary);

        return new DeploymentBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withNamespace(primary.getMetadata().getNamespace())
                        .withName(name)
                        .withLabels(labels)
                        .build())
                .withSpec(new DeploymentSpecBuilder()
                        .withSelector(new LabelSelectorBuilder()
                                .addToMatchLabels(labels)
                                .build())
                        .withTemplate(new PodTemplateSpecBuilder()
                                .withMetadata(new ObjectMetaBuilder()
                                        .withLabels(labels)
                                        .build())
                                .withSpec(new PodSpecBuilder()
                                        .addToVolumes(new VolumeBuilder()
                                                .withName(CONFIG_VOLUME_NAME)
                                                .withConfigMap(new ConfigMapVolumeSourceBuilder()
                                                        .withName(name)
                                                        .build())
                                                .build())
                                        .addToVolumes(dataVolume)
                                        .addToContainers(new ContainerBuilder()
                                                .withName(name)
                                                .withImage(taggedImage)
                                                .withPorts(new ContainerPortBuilder()
                                                        .withName("http")
                                                        .withProtocol("TCP")
                                                        .withContainerPort(DEFAULT_HTTP_PORT)
                                                        .build())
                                                .addToVolumeMounts(new VolumeMountBuilder()
                                                        .withName(CONFIG_VOLUME_NAME)
                                                        .withMountPath(CONFIG_FILE_PATH)
                                                        .withSubPath(CONFIG_FILE_NAME)
                                                        .build())
                                                .addToVolumeMounts(new VolumeMountBuilder()
                                                        .withName(DATA_VOLUME_NAME)
                                                        .withMountPath(DATA_DIR_PATH)
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private Volume desiredDataVolume(DebeziumServer primary) {
        var storageConfig = primary.getSpec().getStorage();
        var builder = new VolumeBuilder().withName(DATA_VOLUME_NAME);

        switch (storageConfig.getType()) {
            case EPHEMERAL ->
                builder.withEmptyDir(new EmptyDirVolumeSourceBuilder()
                        .build());
            case PERSISTENT ->
                builder.withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSourceBuilder()
                        .withClaimName(storageConfig.getClaimName())
                        .build());
        }

        return builder.build();
    }
}
