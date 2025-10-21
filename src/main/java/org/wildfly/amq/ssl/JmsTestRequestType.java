package org.wildfly.amq.ssl;

public enum JmsTestRequestType {
	REQUEST_SEND("send-message"),
	REQUEST_SEND_REQUEST_MESSAGE_FOR_MDB("send-request-message-for-mdb"),
	REQUEST_SEND_REQUEST_MESSAGE_FOR_MDB_AND_KILL_SERVER("send-request-message-for-mdb-and-kill-server"),
	REQUEST_CONSUME_MESSAGE("consume-message"),
	REQUEST_CONSUME_REPLY_MESSAGE_FOR_MDB("consume-reply-message-for-mdb"),
	REQUEST_CONSUME_ALL_REPLY_MESSAGES_FOR_MDB("consume-all-reply-messages-for-mdb");

	private final String value;

	public String value() {
		return value;
	}

	JmsTestRequestType(String value) {
		this.value = value;
	}

	public static JmsTestRequestType fromValue(String value) {
		for (JmsTestRequestType e : values()) {
			if (e.value.equals(value)) {
				return e;
			}
		}
		throw new IllegalArgumentException(
				String.format("Unsupported value for %s: %s", JmsTestRequestType.class.getSimpleName(), value));
	}
}
