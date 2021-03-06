/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.exception;

import static java.util.Arrays.stream;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.mule.runtime.api.component.ComponentIdentifier.buildFromStringRepresentation;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.context.notification.EnrichedNotificationInfo.createInfo;
import static org.mule.runtime.core.api.context.notification.ErrorHandlerNotification.PROCESS_END;
import static org.mule.runtime.core.api.context.notification.ErrorHandlerNotification.PROCESS_START;
import static org.mule.runtime.core.api.processor.MessageProcessors.newChain;
import static org.mule.runtime.core.api.processor.MessageProcessors.processWithChildContext;
import static reactor.core.publisher.Mono.from;
import static reactor.core.publisher.Mono.just;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.context.notification.ErrorHandlerNotification;
import org.mule.runtime.core.api.exception.DisjunctiveErrorTypeMatcher;
import org.mule.runtime.core.api.exception.ErrorTypeMatcher;
import org.mule.runtime.core.api.exception.ErrorTypeRepository;
import org.mule.runtime.core.api.exception.MessagingException;
import org.mule.runtime.core.api.exception.MessagingExceptionHandler;
import org.mule.runtime.core.api.exception.MessagingExceptionHandlerAcceptor;
import org.mule.runtime.core.api.exception.SingleErrorTypeMatcher;
import org.mule.runtime.core.api.management.stats.FlowConstructStatistics;
import org.mule.runtime.core.api.processor.MessageProcessorChain;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.transaction.TransactionCoordination;
import org.mule.runtime.core.internal.message.DefaultExceptionPayload;
import org.mule.runtime.core.internal.message.InternalMessage;
import org.mule.runtime.core.processor.AbstractRequestResponseMessageProcessor;
import org.mule.runtime.core.routing.requestreply.ReplyToPropertyRequestReplyReplier;

import java.util.ArrayList;
import java.util.List;

import org.reactivestreams.Publisher;

import reactor.core.publisher.Mono;

public abstract class TemplateOnErrorHandler extends AbstractExceptionListener
    implements MessagingExceptionHandlerAcceptor {

  private MessageProcessorChain configuredMessageProcessors;
  private Processor replyToMessageProcessor = new ReplyToPropertyRequestReplyReplier();

  private String when;
  private boolean handleException;

  protected String errorType = null;
  protected ErrorTypeMatcher errorTypeMatcher = null;

  @Override
  final public Event handleException(MessagingException exception, Event event) {
    try {
      return new ExceptionMessageProcessor(exception, muleContext, flowConstruct).process(event);
    } catch (MuleException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Publisher<Event> apply(final MessagingException exception) {
    Publisher<Event> publisher =
        new ExceptionMessageProcessor(exception, muleContext, flowConstruct).apply(just(exception.getEvent()));
    return from(publisher).handle((event, sink) -> {
      if (exception.handled()) {
        sink.next(event);
      } else {
        exception.setProcessedEvent(event);
        sink.error(exception);
      }
    });
  }

  @Override
  public void setMessageProcessors(List<Processor> processors) {
    super.setMessageProcessors(processors);
    configuredMessageProcessors = newChain(getMessageProcessors());
  }

  @Override
  protected List<Processor> getOwnedMessageProcessors() {
    return configuredMessageProcessors == null ? new ArrayList<>() : singletonList(configuredMessageProcessors);
  }

  private class ExceptionMessageProcessor extends AbstractRequestResponseMessageProcessor {


    private MessagingException exception;

    public ExceptionMessageProcessor(MessagingException exception, MuleContext muleContext, FlowConstruct flowConstruct) {
      this.exception = exception;
      setMuleContext(muleContext);
      setFlowConstruct(flowConstruct);
      next = new Processor() {

        @Override
        public Event process(Event event) throws MuleException {
          if (!getMessageProcessors().isEmpty()) {
            Event newEvent = Event.builder(event)
                .message(InternalMessage.builder(event.getMessage()).exceptionPayload(new DefaultExceptionPayload(exception))
                    .build())
                .build();
            return configuredMessageProcessors.process(newEvent);
          } else {
            return event;
          }
        }

        @Override
        public Publisher<Event> apply(Publisher<Event> publisher) {
          // TODO MULE-11023 Migrate transaction execution template mechanism to use non-blocking API
          // Use child context if HandleExceptionInterceptor is being used to avoid response being completed twice.
          // TODO MULE-12720 This code makes processCatch/processFinally work for this particular
          // AbstractRequestResponseMessageProcessor, others don't work
          return Mono.from(publisher).flatMapMany(event -> processWithChildContext(event, p -> from(p)
              .flatMapMany(childEvent -> Mono.from(routeAsync(childEvent, exception)))));
        }
      };

    }

    @Override
    protected Event processRequest(Event request) throws MuleException {
      muleContext.getNotificationManager()
          .fireNotification(new ErrorHandlerNotification(createInfo(request, exception, configuredMessageProcessors),
                                                         flowConstruct, PROCESS_START));
      fireNotification(exception, request);
      logException(exception, request);
      processStatistics();

      markExceptionAsHandledIfRequired(exception);
      return beforeRouting(exception, request);
    }

    @Override
    protected Event processResponse(Event response) throws MuleException {
      response = afterRouting(exception, response);
      if (response != null) {
        response = processReplyTo(response, exception);
        closeStream(response.getMessage());
        return nullifyExceptionPayloadIfRequired(response);
      }
      return response;
    }

    @Override
    protected Event processCatch(Event event, MessagingException exception) throws MessagingException {
      try {
        exception.setInErrorHandler(true);
        logger.error("Exception during exception strategy execution");
        doLogException(exception);
        TransactionCoordination.getInstance().rollbackCurrentTransaction();
      } catch (Exception ex) {
        // Do nothing
        logger.warn(ex.getMessage());
      }

      throw exception;
    }

    @Override
    protected void processFinally(Event event, MessagingException exception) {
      muleContext.getNotificationManager()
          .fireNotification(new ErrorHandlerNotification(createInfo(event, exception, configuredMessageProcessors),
                                                         flowConstruct, PROCESS_END));
    }

  }

  private void markExceptionAsHandledIfRequired(Exception exception) {
    if (handleException) {
      markExceptionAsHandled(exception);
    }
  }

  protected void markExceptionAsHandled(Exception exception) {
    if (exception instanceof MessagingException) {
      ((MessagingException) exception).setHandled(true);
    }
  }

  protected Event processReplyTo(Event event, Exception e) {
    try {
      return replyToMessageProcessor.process(event);
    } catch (MuleException ex) {
      logFatal(event, ex);
      return event;
    }
  }

  protected Event nullifyExceptionPayloadIfRequired(Event event) {
    if (this.handleException) {
      return Event.builder(event).error(null).message(InternalMessage.builder(event.getMessage()).exceptionPayload(null).build())
          .build();
    } else {
      return event;
    }
  }

  private void processStatistics() {
    FlowConstructStatistics statistics = flowConstruct.getStatistics();
    if (statistics != null && statistics.isEnabled()) {
      statistics.incExecutionError();
    }
  }

  protected Publisher<Event> routeAsync(Event event, MessagingException t) {
    if (!getMessageProcessors().isEmpty()) {
      event = Event.builder(event)
          .message(InternalMessage.builder(event.getMessage()).exceptionPayload(new DefaultExceptionPayload(t)).build())
          .build();
      return configuredMessageProcessors.apply(just(event));
    }
    return just(event);
  }

  @Override
  protected void doInitialise(MuleContext muleContext) throws InitialisationException {
    super.doInitialise(muleContext);

    if (configuredMessageProcessors != null) {
      configuredMessageProcessors.setFlowConstruct(flowConstruct);
      configuredMessageProcessors.setMuleContext(muleContext);
      configuredMessageProcessors.setMessagingExceptionHandler(messagingExceptionHandler);
    }

    errorTypeMatcher = createErrorType(muleContext.getErrorTypeRepository(), errorType);
  }

  public static ErrorTypeMatcher createErrorType(ErrorTypeRepository errorTypeRepository, String errorTypeNames) {
    if (errorTypeNames == null) {
      return null;
    }
    String[] errorTypeIdentifiers = errorTypeNames.split(",");
    List<ErrorTypeMatcher> matchers = stream(errorTypeIdentifiers).map((identifier) -> {
      String parsedIdentifier = identifier.trim();
      return new SingleErrorTypeMatcher(errorTypeRepository.lookupErrorType(buildFromStringRepresentation(parsedIdentifier))
          .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("Could not find ErrorType for the given identifier: '%s'",
                                                                          parsedIdentifier))));
    }).collect(toList());
    return new DisjunctiveErrorTypeMatcher(matchers);
  }

  public void setWhen(String when) {
    this.when = when;
  }


  @Override
  public boolean accept(Event event) {
    return acceptsAll() || acceptsErrorType(event)
        || (when != null && muleContext.getExpressionManager().evaluateBoolean(when, event, flowConstruct));
  }


  private boolean acceptsErrorType(Event event) {
    return errorTypeMatcher != null && errorTypeMatcher.match(event.getError().get().getErrorType());
  }

  @Override
  public boolean acceptsAll() {
    return errorTypeMatcher == null && when == null;
  }

  protected Event afterRouting(MessagingException exception, Event event) {
    return event;
  }

  protected Event beforeRouting(MessagingException exception, Event event) {
    return event;
  }

  @Override
  public void setMessagingExceptionHandler(MessagingExceptionHandler messagingExceptionHandler) {
    return;
  }

  public void setHandleException(boolean handleException) {
    this.handleException = handleException;
  }

  public void setErrorType(String errorType) {
    this.errorType = errorType;
  }
}
