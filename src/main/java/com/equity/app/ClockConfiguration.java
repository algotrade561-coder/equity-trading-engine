package com.equity.app;

import com.equity.platform.time.SystemTradingClock;
import com.equity.platform.time.TradingClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the one clock the system is allowed to read. REPLAY replaces this bean with a tape
 * clock; nothing else changes, which is what keeps LIVE and REPLAY on identical strategy code.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public TradingClock tradingClock() {
        return new SystemTradingClock();
    }
}
