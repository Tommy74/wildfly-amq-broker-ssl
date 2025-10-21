package org.wildfly.amq.ssl;

import jakarta.annotation.Resource;
import jakarta.inject.Inject;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSDestinationDefinition;
import jakarta.jms.JMSDestinationDefinitions;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.PrintWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Test servlet which can be used to invoke common JMS tasks in test classes.
 */
@JMSDestinationDefinitions(
        value = {
                @JMSDestinationDefinition(
                        name = "java:/queue/testQueue",
                        interfaceName = "jakarta.jms.Queue",
                        destinationName = "test-queue",
                        properties = {"enable-amq1-prefix=false"}
                ),
                @JMSDestinationDefinition(
                        name = "java:/queue/inQueue",
                        interfaceName = "jakarta.jms.Queue",
                        destinationName = "in-queue",
                        properties = {"enable-amq1-prefix=false"}
                ),
                @JMSDestinationDefinition(
                        name = "java:/queue/outQueue",
                        interfaceName = "jakarta.jms.Queue",
                        destinationName = "out-queue",
                        properties = {"enable-amq1-prefix=false"}
                )
        }
)
@WebServlet("/jms-test")
public class JmsTestServlet extends HttpServlet {

	private static final Logger LOGGER = Logger.getLogger(JmsTestServlet.class.toString());

	@Resource(lookup="java:/queue/testQueue")
	private Queue testQueue;

	@Resource(lookup="java:/queue/inQueue")
	private Queue inQueue;

	@Resource(lookup="java:/queue/outQueue")
	private Queue outQueue;

	@Inject()
	private JMSContext jmsContext;

	@Resource(lookup = "java:jboss/DefaultJMSConnectionFactory")
	private ConnectionFactory connectionFactory;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
		resp.setContentType("text/html");
		TextMessage textMessage;

		String request = req.getParameter("request");

		try (PrintWriter out = resp.getWriter()) {

			if (request == null || "".equals(request)) { // log usage and return
				logUsage(out, null);
				return;
			}

			switch (JmsTestRequestType.fromValue(request)) {
				case REQUEST_SEND:
					textMessage = jmsContext.createTextMessage(JmsTestConstants.QUEUE_TEXT_MESSAGE);
					jmsContext.createProducer().send(testQueue, textMessage);
					out.println(JmsTestConstants.QUEUE_SEND_RESPONSE + testQueue.toString());
					break;
				// produce and send a text message to testQueue
				case REQUEST_SEND_REQUEST_MESSAGE_FOR_MDB:
					textMessage = jmsContext.createTextMessage(JmsTestConstants.QUEUE_MDB_TEXT_MESSAGE);
					jmsContext.createProducer().send(inQueue, textMessage);
					out.println(JmsTestConstants.QUEUE_MDB_SEND_RESPONSE + inQueue.toString());
					break;
				// produce 180 text messages to inQueue, MDB will kill server when 100th message is consumed
				// this must done in transaction to avoid situation that MDB kill server before this call is finished
				// note that JMSContext cannot be used as Wildfly/EAP does no allow to inject it with transacted session
				case REQUEST_SEND_REQUEST_MESSAGE_FOR_MDB_AND_KILL_SERVER:
					String messageCount = req.getParameter("messageCount");
					for (String name : req.getParameterMap().keySet()) {
						System.out.println("param" + name);
					}
					System.out.println("messageCount" + messageCount);
					int messageCountToSend = isEmpty(messageCount) ? 180 : Integer.parseInt(messageCount);
					try (Connection connection = connectionFactory.createConnection()) {
						Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
						MessageProducer producer = session.createProducer(inQueue);
						for (int i = 0; i < messageCountToSend; i++) {
							textMessage = session.createTextMessage(JmsTestConstants.QUEUE_MDB_TEXT_MESSAGE);
							producer.send(textMessage);
						}
						out.println(messageCountToSend + " messages were sent into queue: " + inQueue.toString());
						session.commit();
					} catch (Exception ex) {
						ex.printStackTrace();
						out.println(ex);
					}
					break;
				// consume a text message from testQueue
				case REQUEST_CONSUME_MESSAGE:
					textMessage = (TextMessage) jmsContext.createConsumer(testQueue).receive(1000);
					out.println(textMessage.getText());
					break;
				// consume a reply text message from outQueue, processed by MDB
				case REQUEST_CONSUME_REPLY_MESSAGE_FOR_MDB:
					textMessage = (TextMessage) jmsContext.createConsumer(outQueue).receive(1000);
					out.println((textMessage == null ? null : textMessage.getText()) + " message details: " + textMessage);
					break;
				// consume all reply text messages from outQueue, processed by MDB
				case REQUEST_CONSUME_ALL_REPLY_MESSAGES_FOR_MDB:
					JMSConsumer consumer = jmsContext.createConsumer(outQueue);
					int count = 0;
					while ((consumer.receive(1000)) != null) {
						count++;
					}
					out.print(count);
					break;
				// print usage
				default:
					logUsage(out, request);
			}
		} catch (Exception ex) {
			LOGGER.log(Level.SEVERE, ex.getMessage());
		}
	}

	private boolean isEmpty(String value) {
		return value == null || "".equals(value);
	}

	private void logUsage(PrintWriter out, String request) {
		out.println("Invalid request parameter: " + request + "<br>" +
				"Usage:<ul>" +
				"<li>use <b>?request=" + JmsTestRequestType.REQUEST_SEND.value()
				+ "</b> parameter to send a message to test queue</li>" +
				"<li>use <b>?request=" + JmsTestRequestType.REQUEST_SEND_REQUEST_MESSAGE_FOR_MDB.value()
				+ " </b> parameter to send a message " +
				"to test queue</li>" +
				"<li>use <b>?request=" + JmsTestRequestType.REQUEST_SEND_REQUEST_MESSAGE_FOR_MDB_AND_KILL_SERVER.value()
				+ " </b> parameter " +
				"to send 180 messages to test queue, MDB will kill server when 100th message is received," +
				"you can use paramter &messageCount=20 to change number of messages</li>" +
				"<li>use <b>?request=" + JmsTestRequestType.REQUEST_CONSUME_MESSAGE.value()
				+ "</b> parameter to consume a message from test queue</li>" +
				"<li>use <b>?request=" + JmsTestRequestType.REQUEST_CONSUME_REPLY_MESSAGE_FOR_MDB.value()
				+ "</b> parameter to consume a reply " +
				"message from outQueue queue which was processed by MDB</li>" +
				"<li>use <b>?request=" + JmsTestRequestType.REQUEST_CONSUME_ALL_REPLY_MESSAGES_FOR_MDB.value()
				+ "</b> parameter to consume all reply " +
				"messages from outQueue queue which were processed by MDB</li>" +
				"</ul>");
	}
}
