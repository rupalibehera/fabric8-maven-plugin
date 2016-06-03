/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.core.handler;

import java.util.*;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.maven.core.config.ServiceConfiguration;
import io.fabric8.utils.Strings;

/**
 * @author roland
 * @since 08/04/16
 */
public class ServiceHandler {

    public Service getService(ServiceConfiguration service, Map<String, String> annotations) {
        List<Service> ret = getServices(Collections.singletonList(service),annotations);
        return ret.size() > 0 ? ret.get(0) : null;
    }

    public List<Service> getServices(List<ServiceConfiguration> services, Map<String, String> annotations) {

        ArrayList<Service> ret = new ArrayList<>();

        for (ServiceConfiguration service : services) {
            ServiceBuilder serviceBuilder = new ServiceBuilder()
                .withNewMetadata()
                  .withName(service.getName())
                  .withAnnotations(annotations)
                .endMetadata();

            ServiceFluent.SpecNested<ServiceBuilder> serviceSpecBuilder =
                serviceBuilder.withNewSpec();

            List<ServicePort> servicePorts = new ArrayList<>();
            for (ServiceConfiguration.Port port : service.getPorts()) {
                ServicePort servicePort = new ServicePortBuilder()
                    .withName(port.getName())
                    .withProtocol(port.getProtocol().name())
                    .withTargetPort(new IntOrString(port.getTargetPort()))
                    .withPort(port.getPort())
                    .withNodePort(port.getNodePort())
                    .build();
                servicePorts.add(servicePort);
            }

            if (!servicePorts.isEmpty()) {
                serviceSpecBuilder.withPorts(servicePorts);
            }

            if (service.isHeadless()) {
                serviceSpecBuilder.withClusterIP("None");
            }

            if (!Strings.isNullOrBlank(service.getType())) {
                serviceSpecBuilder.withType(service.getType());
            }
            serviceSpecBuilder.endSpec();

            if (service.isHeadless() || !servicePorts.isEmpty()) {
                ret.add(serviceBuilder.build());
            }
        }
        return ret;
    }
}