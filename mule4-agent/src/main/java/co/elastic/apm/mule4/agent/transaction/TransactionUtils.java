package co.elastic.apm.mule4.agent.transaction;

import java.lang.reflect.Field;
import java.util.Optional;

import org.mule.runtime.api.event.Event;
import org.mule.runtime.api.notification.PipelineMessageNotification;
import org.mule.runtime.core.api.event.CoreEvent;

import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Transaction;

/*
 * Handling of Transaction starts and ends
 */
public class TransactionUtils {

	/*
	 * Is this notification related to newly started flow, i.e. there is no
	 * transaction yet in the transactionStore?
	 */
	public static boolean isFirstEvent(TransactionStore transactionStore, PipelineMessageNotification notification) {
		return !transactionStore.isTransactionPresent(getTransactionId(notification).get());
	}

	/*
	 * Start transaction
	 */
	public static void startTransaction(TransactionStore transactionStore, PipelineMessageNotification notification) {
		Transaction transaction;

		// Check if it has parent trace-id setting, or should be started as brand new
		// transaction.
		if (hasRemoteParent(notification))
			transaction = ElasticApm.startTransactionWithRemoteParent(x -> getHeaderExtractor(x, notification));
		else {
			transaction = ElasticApm.startTransaction();
			// TODO: Check if ensuring parent-id is required.
			// transaction.ensureParentId();
		}

		// Once created, store the transaction in the store.
		transactionStore.storeTransaction(getTransactionId(notification).get(),
				populateTransactionDetails(transaction, notification));
	}

	/*
	 * Capture all relevant transaction details.
	 */
	private static Transaction populateTransactionDetails(Transaction transaction,
			PipelineMessageNotification notification) {

		ApmTransaction transaction2 = new ApmTransaction(transaction);

		String flowName = getFlowName(notification);
		transaction2.setName(flowName);
		transaction2.setFlowName(flowName);

		transaction2.setType(Transaction.TYPE_REQUEST);

		// TODO: investigate population of transaction start from the external
		// parameters.
		// transaction.setStartTimestamp(epochMicros);

		return transaction2;
	}

	private static String getFlowName(PipelineMessageNotification notification) {
		return notification.getResourceIdentifier();
	}

	/*
	 * Retrieve the transactionId by getting the attached event to pipeline
	 * notification or exception
	 */
	private static Optional<String> getTransactionId(PipelineMessageNotification notification) {

		Event event = notification.getEvent();

		if (event != null)
			return Optional.of(event.getCorrelationId());

		// If the event == null, this must be the case of an exception and the original
		// event is attached under processedEvent in the exception.
		Exception e = notification.getInfo().getException();

		// This is a really ugly hack to get around the fact that
		// org.mule.runtime.core.internal.exception.MessagingException class is not
		// visible in the current classloader and there is no documentation to explain
		// how to access objects of this class and why the hell it is internal and is
		// not part of the API.
		// TODO: raise why org.mule.runtime.core.internal.exception.MessagingException
		// is not part of the API with Mule support.
		Field f = null;
		CoreEvent iWantThis = null;
		try {
			f = e.getClass().getDeclaredField("processedEvent");
		} catch (NoSuchFieldException | SecurityException e1) {
			e1.printStackTrace();
		} // NoSuchFieldException
		f.setAccessible(true);
		try {
			iWantThis = (CoreEvent) f.get(e);
		} catch (IllegalArgumentException | IllegalAccessException e1) {
			e1.printStackTrace();
		} // IllegalAccessException

		if (iWantThis != null) {
			String correlationId = iWantThis.getCorrelationId();
			return Optional.of(correlationId);
		}

		return Optional.empty();
	}

	private static String getHeaderExtractor(String x, PipelineMessageNotification notification) {
		// TODO provide parent trace info header extractor to support distributed
		// transactions
		return null;
	}

	private static boolean hasRemoteParent(PipelineMessageNotification notification) {
		// TODO Determine if the notification was published for a request with remote
		// parent information.
		return false;
	}

	/*
	 * End transaction.
	 */
	public static void endTransaction(TransactionStore transactionStore, PipelineMessageNotification notification) {

		// We only create and end transactions related to the top level flow. All the
		// rest of the flows invoked through flow-ref are not represented as
		// transactions and ignored. Only the corresponding flow-ref step is represented
		// as Span.
		if (!isEndOfTopFlow(transactionStore, notification))
			return;

		Transaction transaction = transactionStore.retrieveTransaction(getTransactionId(notification).get());

		populateFinalTransactionDetails(transaction, notification);

		transaction.end();
	}

	private static boolean isEndOfTopFlow(TransactionStore transactionStore, PipelineMessageNotification notification) {

		Optional<String> transactionId = getTransactionId(notification);

		if (!transactionId.isPresent())
			return false;

		Optional<Transaction> optional = transactionStore.getTransaction(transactionId.get());

		if (!optional.isPresent())
			return false;

		ApmTransaction transaction = (ApmTransaction) optional.get();

		if (transaction.getFlowName().equals(getFlowName(notification)))
			return true;

		return false;
	}

	private static void populateFinalTransactionDetails(Transaction transaction,
			PipelineMessageNotification notification) {
		// Noop
		// TODO: Populate the output properties
		// TODO: Populate final flowVars
		// TODO: Populate the transaction status
	}

}
