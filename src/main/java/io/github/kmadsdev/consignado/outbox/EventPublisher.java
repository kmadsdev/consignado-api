package io.github.kmadsdev.consignado.outbox;

/**
 * Port to the message channel. The relay calls it once per event and treats a normal
 * return as "accepted by the channel". A broker adapter must therefore return only after
 * the broker acknowledged the message, and throw otherwise.
 *
 * <p>The only adapter today is {@link LoggingEventPublisher}. See
 * docs/adr/0005-transactional-outbox.md for how a broker adapter plugs in.
 */
public interface EventPublisher {

    void publish(OutboxMessage message);
}
