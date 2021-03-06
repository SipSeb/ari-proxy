package io.retel.ariproxy.boundary.events;

import static io.retel.ariproxy.config.ServiceConfig.KAFKA_COMMANDS_TOPIC;
import static io.retel.ariproxy.config.ServiceConfig.KAFKA_EVENTS_AND_RESPONSES_TOPIC;
import static io.vavr.API.None;
import static io.vavr.API.Some;

import akka.NotUsed;
import akka.actor.ActorRef;
import akka.event.LoggingAdapter;
import akka.http.javadsl.model.ws.Message;
import akka.stream.javadsl.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import io.retel.ariproxy.akkajavainterop.PatternsAdapter;
import io.retel.ariproxy.boundary.callcontext.api.CallContextProvided;
import io.retel.ariproxy.boundary.callcontext.api.ProvideCallContext;
import io.retel.ariproxy.boundary.callcontext.api.ProviderPolicy;
import io.retel.ariproxy.boundary.commandsandresponses.auxiliary.AriMessageEnvelope;
import io.retel.ariproxy.boundary.commandsandresponses.auxiliary.AriMessageType;
import io.retel.ariproxy.config.ServiceConfig;
import io.retel.ariproxy.metrics.IncreaseCounter;
import io.retel.ariproxy.metrics.StartCallSetupTimer;
import com.typesafe.config.Config;
import io.vavr.Tuple;
import io.vavr.collection.List;
import io.vavr.collection.Seq;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.ProducerRecord;

public class AriEventProcessing {

	private static final Config serviceConfig = ServiceConfig.INSTANCE.get();
	private static final String ARI_COMMANDS_TOPIC = serviceConfig.getString(KAFKA_COMMANDS_TOPIC);
	private static final String ARI_EVENST_AND_RESPONSES_TOPIC = serviceConfig
			.getString(KAFKA_EVENTS_AND_RESPONSES_TOPIC);

	public static Seq<MetricsGatherer> determineMetricsGatherer(AriMessageType type) {

		List<MetricsGatherer> metricsGatherers = List.of(callContextSupplier -> new IncreaseCounter(type.name()));

		switch (type) {
			case STASIS_START:
				metricsGatherers = metricsGatherers.appendAll(List.of(
						callContextSupplier -> new IncreaseCounter("CallsStarted"),
						callContextSupplier -> new StartCallSetupTimer(callContextSupplier.get())
				));
				break;
			case STASIS_END:
				metricsGatherers = metricsGatherers.append(
						callContextSupplier -> new IncreaseCounter("CallsEnded")
				);
				break;
		}

		return metricsGatherers;
	}

	public static Source<ProducerRecord<String, String>, NotUsed> generateProducerRecordFromEvent(
			Message message,
			ActorRef callContextProvider,
			LoggingAdapter log,
			Runnable applicationReplacedHandler) {

		final String messageBody = message.asTextMessage().getStrictText();
		final String eventTypeString = getValueFromMessageByPath(message, "/type").getOrElseThrow(t -> t);
		final AriMessageType ariMessageType = AriMessageType.fromType(eventTypeString);

		if (AriMessageType.APPLICATION_REPLACED.equals(ariMessageType)) {
			log.info("Got APPLICATION_REPLACED event, shutting down...");
			applicationReplacedHandler.run();
			return Source.empty();
		}

		return ariMessageType.extractResourceIdFromBody(messageBody)
				.map(resourceIdTry -> resourceIdTry
						.flatMap(id -> getCallContext(
								id,
								callContextProvider,
								AriMessageType.STASIS_START.equals(ariMessageType)
										? ProviderPolicy.CREATE_IF_MISSING
										: ProviderPolicy.LOOKUP_ONLY
						).map(cc -> Tuple.of(id, cc)))
						.map(idAndCc -> createSource(ariMessageType, log, idAndCc._1, idAndCc._2, messageBody))
				)
				.getOrElse(Try.success(Source.empty()))
				.getOrElseThrow(t -> new RuntimeException(t));
	}

	private static Source<ProducerRecord<String, String>, NotUsed> createSource(
			AriMessageType type,
			LoggingAdapter log,
			String resourceId,
			String callContext,
			String messageBody) {

		final AriMessageEnvelope envelope = new AriMessageEnvelope(
				type,
				ARI_COMMANDS_TOPIC,
				messageBody,
				resourceId
		);

		log.debug("[ARI MESSAGE TYPE] {}", envelope.getType());
		return Source.single(new ProducerRecord<>(
				ARI_EVENST_AND_RESPONSES_TOPIC,
				callContext,
				new Gson().toJson(envelope)
		));
	}

	public static Try<String> getCallContext(String resourceId, ActorRef callContextProvider,
			ProviderPolicy providerPolicy) {
		return PatternsAdapter.<CallContextProvided>ask(
				callContextProvider,
				new ProvideCallContext(resourceId, providerPolicy),
				100
		)
				.map(provided -> provided.callContext())
				.toTry();
	}

	public static Either<RuntimeException, String> getValueFromMessageByPath(Message message, String path) {
		return Try.of(() -> new ObjectMapper().readTree(message.asTextMessage().getStrictText()))
				.toOption()
				.flatMap(root -> Option.of(root.at(path)))
				.map(JsonNode::asText)
				.flatMap(type -> StringUtils.isBlank(type) ? None() : Some(type))
				.toEither(() -> new RuntimeException(message.asTextMessage().getStrictText()));
	}
}

@FunctionalInterface
interface MetricsGatherer {

	Object withCallContextSupplier(Supplier<String> callContextSupplier);
}
