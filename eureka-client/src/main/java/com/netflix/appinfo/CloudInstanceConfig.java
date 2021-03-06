/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.appinfo;

import java.util.HashMap;
import java.util.Map;

import com.google.inject.ProvidedBy;
import com.netflix.appinfo.AmazonInfo.MetaDataKey;
import com.netflix.appinfo.DataCenterInfo.Name;
import com.netflix.appinfo.providers.CloudInstanceConfigProvider;
import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicPropertyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;

/**
 * An {@link InstanceInfo} configuration for AWS cloud deployments.
 *
 * <p>
 * The information required for registration with eureka by a combination of
 * user-supplied values as well as querying AWS instance metadata.An utility
 * class {@link AmazonInfo} helps in retrieving AWS specific values. Some of
 * that information including <em>availability zone</em> is used for determining
 * which eureka server to communicate to.
 * </p>
 *
 * @author Karthik Ranganathan
 *
 */
@Singleton
@ProvidedBy(CloudInstanceConfigProvider.class)
public class CloudInstanceConfig extends PropertiesInstanceConfig {
    private static final Logger logger = LoggerFactory.getLogger(CloudInstanceConfig.class);
    private static final DynamicPropertyFactory INSTANCE = DynamicPropertyFactory.getInstance();

    private static final String[] DEFAULT_AWS_ADDRESS_RESOLUTION_ORDER = new String[] {
            MetaDataKey.publicHostname.name(),
            MetaDataKey.localIpv4.name()
    };

    private DynamicBooleanProperty propValidateInstanceId;
    private volatile AmazonInfo info;

    public CloudInstanceConfig() {
        initCloudInstanceConfig(namespace);
    }

    public CloudInstanceConfig(String namespace) {
        super(namespace);
        initCloudInstanceConfig(namespace);
    }

    private void initCloudInstanceConfig(String namespace) {
        propValidateInstanceId = INSTANCE.getBooleanProperty(namespace + "validateInstanceId", true);
        info = initDataCenterInfo();
    }

    private AmazonInfo initDataCenterInfo() {
        AmazonInfo info;
        try {
            info = AmazonInfo.Builder.newBuilder().autoBuild(namespace);
            logger.info("Datacenter is: " + Name.Amazon);
        } catch (Throwable e) {
            logger.error("Cannot initialize amazon info :", e);
            throw new RuntimeException(e);
        }
        // Instance id being null means we could not get the amazon metadata
        if (info.get(MetaDataKey.instanceId) == null) {
            if (propValidateInstanceId.get()) {
                throw new RuntimeException(
                        "Your datacenter is defined as cloud but we are not able to get the amazon metadata to "
                                + "register. \nSet the property " + namespace + "validateInstanceId to false to "
                                + "ignore the metadata call");
            } else {
                // The property to not validate instance ids may be set for
                // development and in that scenario, populate instance id
                // and public hostname with the hostname of the machine
                Map<String, String> metadataMap = new HashMap<String, String>();
                metadataMap.put(MetaDataKey.instanceId.getName(), super.getIpAddress());
                metadataMap.put(MetaDataKey.publicHostname.getName(), super.getHostName(false));
                info.setMetadata(metadataMap);
            }
        } else if ((info.get(MetaDataKey.publicHostname) == null)
                && (info.get(MetaDataKey.localIpv4) != null)) {
            // :( legacy code and logic
            // This might be a case of VPC where the instance id is not null, but
            // public hostname might be null
            info.getMetadata().put(MetaDataKey.publicHostname.getName(), (info.get(MetaDataKey.localIpv4)));
        }
        return info;
    }

    public String resolveDefaultAddress(DataCenterInfo dataCenterInfo) {
        String result = getHostName(true);

        if (dataCenterInfo instanceof AmazonInfo) {
            AmazonInfo amazonInfo = (AmazonInfo) dataCenterInfo;
            for (String name : getDefaultAddressResolutionOrder()) {
                try {
                    AmazonInfo.MetaDataKey key = AmazonInfo.MetaDataKey.valueOf(name);
                    String address = amazonInfo.get(key);
                    if (address != null && !address.isEmpty()) {
                        result = address;
                        break;
                    }
                } catch (Exception e) {
                    logger.error("failed to resolve default address for key {}, skipping", name, e);
                }
            }
        } else {
            logger.warn("DataCenterInfo is not of type AmazonInfo. Defaulting to default resolution");
        }

        return result;
    }

    @Override
    public String getHostName(boolean refresh) {
        if (refresh) {
            refreshAmazonInfo();
        }
        return info.get(MetaDataKey.publicHostname);
    }

    @Override
    public String getIpAddress() {
        String ipAddr = info.get(MetaDataKey.localIpv4);
        return ipAddr == null ? super.getIpAddress() : ipAddr;
    }

    @Override
    public DataCenterInfo getDataCenterInfo() {
        return info;
    }

    @Override
    public String[] getDefaultAddressResolutionOrder() {
        String[] order = super.getDefaultAddressResolutionOrder();
        return (order.length == 0) ? DEFAULT_AWS_ADDRESS_RESOLUTION_ORDER : order;
    }

    /**
     * Refresh instance info - currently only used when in AWS cloud
     * as a public ip can change whenever an EIP is associated or dissociated.
     */
    public synchronized void refreshAmazonInfo() {
        try {
            AmazonInfo newInfo = AmazonInfo.Builder.newBuilder().autoBuild(namespace);
            if (!newInfo.equals(info)) {
                // the datacenter info has changed, re-sync it
                logger.warn("The AmazonInfo changed from : {} => {}", info, newInfo);
                this.info = newInfo;
            }
        } catch (Throwable t) {
            logger.error("Cannot refresh the Amazon Info ", t);
        }
    }
}
