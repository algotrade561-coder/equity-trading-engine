package com.equity.app;

import com.equity.broker.kite.KiteTickerManager;
import com.equity.domain.user.UserId;
import com.equity.platform.time.TradingClock;
import com.equity.provisioning.AwsEc2NetworkClient;
import com.equity.provisioning.Ec2NetworkClient;
import com.equity.provisioning.InstanceMetadata;
import com.equity.provisioning.IpAllocationService;
import com.equity.provisioning.IpAllocationStore;
import com.equity.provisioning.LinuxOsSecondaryIpConfigurer;
import com.equity.provisioning.OsSecondaryIpConfigurer;
import com.equity.provisioning.ProvisioningProperties;
import com.equity.provisioning.RecordingEc2NetworkClient;
import com.equity.provisioning.RecordingOsConfigurer;
import com.equity.store.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the per-user address automation: real AWS when enabled, recording fakes otherwise.
 *
 * <p>The flag is the only thing that decides. With it off — every laptop, and the production box
 * until somebody turns it on — the recording implementations are wired, the whole screen works, and
 * nothing is ever asked of AWS or the OS. With it on, the SDK client is built against the instance's
 * IAM role and the OS configurer runs {@code ip addr}. There is no mixed mode: a real EC2 client
 * with a fake OS would allocate addresses nothing can bind to.</p>
 */
@Configuration
public class ProvisioningConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningConfiguration.class);

    @Bean
    InstanceMetadata instanceMetadata(ProvisioningProperties properties) {
        return new InstanceMetadata(properties);
    }

    @Bean
    Ec2NetworkClient ec2NetworkClient(ProvisioningProperties properties, InstanceMetadata metadata) {
        if (properties.isEnabled()) {
            log.warn("PROVISIONING IS ON: this process can allocate Elastic IPs, which cost money, "
                    + "and change the addresses on this instance's network interface");
            return new AwsEc2NetworkClient(metadata);
        }
        log.info("provisioning is off — EC2 calls are recorded, not made");
        return new RecordingEc2NetworkClient();
    }

    @Bean
    OsSecondaryIpConfigurer osSecondaryIpConfigurer(ProvisioningProperties properties) {
        return properties.isEnabled()
                ? new LinuxOsSecondaryIpConfigurer(properties)
                : new RecordingOsConfigurer();
    }

    /**
     * The one place the automation touches the trading side: set the address, drop the old client,
     * reopen the ticker. Both halves in one call, because doing one without the other is the
     * reference project's recorded first bug.
     */
    @Bean
    IpAllocationService.SourceIpBinder sourceIpBinder(UserProfileService profiles, KiteTickerManager tickers) {
        return new IpAllocationService.SourceIpBinder() {
            @Override
            public void bind(UserId userId, String privateIp, String actor) {
                String previous = profiles.setSourceIp(userId, privateIp);
                tickers.sourceIpChanged(userId, previous);
            }

            @Override
            public void unbind(UserId userId, String actor) {
                String previous = profiles.setSourceIp(userId, null);
                tickers.sourceIpChanged(userId, previous);
            }
        };
    }

    @Bean
    IpAllocationService ipAllocationService(ProvisioningProperties properties, InstanceMetadata metadata,
                                            Ec2NetworkClient ec2, OsSecondaryIpConfigurer os,
                                            IpAllocationStore store,
                                            IpAllocationService.SourceIpBinder binder, TradingClock clock) {
        return new IpAllocationService(properties, metadata, ec2, os, store, binder, clock);
    }
}
