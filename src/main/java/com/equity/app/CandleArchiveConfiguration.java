package com.equity.app;

import com.equity.platform.time.TradingClock;
import com.equity.store.CandleArchive;
import com.equity.store.CandleArchiver;
import com.equity.store.CandleRepository;
import com.equity.store.LocalDirectoryCandleArchive;
import com.equity.store.S3CandleArchive;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Where finished sessions go. A bucket name selects S3; nothing selects a local directory.
 *
 * <p>No AWS credentials are configured anywhere: on the instance the SDK finds the instance role
 * through IMDSv2. A developer machine without a bucket configured never constructs an S3 client and
 * so never looks for credentials.</p>
 */
@Configuration
public class CandleArchiveConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CandleArchiveConfiguration.class);

    @Bean
    public CandleArchive candleArchive(
            @Value("${equity.candles.archive.s3-bucket:}") String bucket,
            @Value("${equity.candles.archive.s3-prefix:candles}") String prefix,
            @Value("${equity.candles.archive.directory:./data/candle-archive}") String directory,
            @Value("${equity.provisioning.region:}") String region) {
        if (bucket == null || bucket.isBlank()) {
            log.info("candle archive is a local directory ({}); set CANDLE_ARCHIVE_BUCKET to use S3", directory);
            return new LocalDirectoryCandleArchive(Path.of(directory));
        }
        S3Client s3 = region == null || region.isBlank()
                ? S3Client.builder().build()
                : S3Client.builder().region(Region.of(region)).build();
        return new S3CandleArchive(s3, bucket.trim(), prefix);
    }

    @Bean
    public CandleArchiver candleArchiver(
            CandleRepository repository, CandleArchive archive,
            PlatformTransactionManager transactions, TradingClock clock,
            @Value("${equity.candles.archive.work-directory:./data/candle-archive/.work}") String workDirectory,
            @Value("${equity.candles.retention-days:7}") int retentionDays,
            @Value("${equity.candles.hard-limit-days:60}") int hardLimitDays) {
        return new CandleArchiver(repository, archive, transactions, clock,
                Path.of(workDirectory), retentionDays, hardLimitDays);
    }

    /**
     * Runs the archiver a few minutes after start-up and then every six hours. The archiver itself
     * declines to run during market hours, so on the trading box this is once each morning.
     */
    @Component
    public static class CandleArchiveSchedule {
        private final CandleArchiver archiver;
        private final boolean enabled;

        public CandleArchiveSchedule(CandleArchiver archiver,
                                     @Value("${equity.candles.persist:true}") boolean enabled) {
            this.archiver = archiver;
            this.enabled = enabled;
        }

        @Scheduled(initialDelay = 180_000, fixedDelay = 6 * 3_600_000)
        public void run() {
            if (!enabled) return;
            try {
                archiver.scheduled();
            } catch (RuntimeException e) {
                log.error("candle archive run failed: {}", e.toString(), e);
            }
        }
    }
}
