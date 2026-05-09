package org.arguslog.api.billing.adapter.out.nowpayments;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NowPaymentsProperties.class)
public class NowPaymentsConfig {}
