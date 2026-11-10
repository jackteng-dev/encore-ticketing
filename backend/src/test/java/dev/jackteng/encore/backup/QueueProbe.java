package dev.jackteng.encore.backup;

import com.rabbitmq.client.ConnectionFactory;

/** Test-classpath-only passive observation. No Spring/RabbitAdmin/topology mutations. */
public final class QueueProbe {
    public static void main(String[] args) throws Exception {
        var namespace = System.getenv("TICKET_MQ_NAMESPACE");
        if (namespace == null || !namespace.matches("encore-[a-z0-9-]{1,48}")) throw new IllegalArgumentException("Own namespace required");
        var factory = new ConnectionFactory();
        factory.setHost(System.getenv("TICKET_MQ_HOST"));
        factory.setPort(Integer.parseInt(System.getenv("TICKET_MQ_PORT")));
        factory.setUsername(System.getenv("TICKET_MQ_USER"));
        factory.setPassword(System.getenv("TICKET_MQ_PASSWORD"));
        factory.setVirtualHost(System.getenv("TICKET_MQ_VHOST"));
        factory.setAutomaticRecoveryEnabled(false);
        factory.setConnectionTimeout(2000);
        factory.setHandshakeTimeout(2000);
        factory.setChannelRpcTimeout(2000);
        try (var connection = factory.newConnection("ticket-backup-readonly-probe"); var channel = connection.createChannel()) {
            for (var suffix : java.util.List.of("purchase", "retry", "dead")) {
                var reply = channel.queueDeclarePassive(namespace + "." + suffix + ".q");
                System.out.println(suffix + "|" + reply.getMessageCount() + "|" + reply.getConsumerCount());
            }
        }
    }
}
