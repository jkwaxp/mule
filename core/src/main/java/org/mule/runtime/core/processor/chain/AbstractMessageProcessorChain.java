/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.processor.chain;

import static org.apache.commons.lang3.StringUtils.replace;
import static org.mule.runtime.core.api.Event.setCurrentEvent;
import static org.mule.runtime.core.api.context.notification.MessageProcessorNotification.MESSAGE_PROCESSOR_POST_INVOKE;
import static org.mule.runtime.core.api.context.notification.MessageProcessorNotification.MESSAGE_PROCESSOR_PRE_INVOKE;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.setFlowConstructIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.setMuleContextIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.stopIfNeeded;
import static org.mule.runtime.core.api.processor.MessageProcessors.processToApply;
import static org.mule.runtime.core.api.util.ExceptionUtils.NULL_ERROR_HANDLER;
import static org.mule.runtime.core.api.util.ExceptionUtils.updateMessagingExceptionWithError;
import static org.mule.runtime.core.api.util.StringUtils.isBlank;
import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.publisher.Flux.empty;
import static reactor.core.publisher.Flux.from;
import static reactor.core.publisher.Flux.just;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lifecycle.Startable;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.meta.AbstractAnnotatedObject;
import org.mule.runtime.api.meta.AnnotatedObject;
import org.mule.runtime.api.streaming.CursorProvider;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.construct.Pipeline;
import org.mule.runtime.core.api.context.notification.MessageProcessorNotification;
import org.mule.runtime.core.api.context.notification.ServerNotificationManager;
import org.mule.runtime.core.api.exception.MessagingException;
import org.mule.runtime.core.api.exception.MessagingExceptionHandler;
import org.mule.runtime.core.api.exception.MessagingExceptionHandlerAware;
import org.mule.runtime.core.api.processor.MessageProcessorChain;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.processor.ReactiveProcessor;
import org.mule.runtime.core.api.registry.RegistrationException;
import org.mule.runtime.core.api.util.ExceptionUtils;
import org.mule.runtime.core.processor.interceptor.ReactiveInterceptorAdapter;
import org.mule.runtime.core.streaming.StreamingManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Builder needs to return a composite rather than the first MessageProcessor in the chain. This is so that if this chain is
 * nested in another chain the next MessageProcessor in the parent chain is not injected into the first in the nested chain.
 */
abstract class AbstractMessageProcessorChain extends AbstractAnnotatedObject implements MessageProcessorChain {

  private static final Logger LOGGER = getLogger(AbstractMessageProcessorChain.class);

  protected final String name;
  protected final List<Processor> processors;
  protected MuleContext muleContext;
  protected FlowConstruct flowConstruct;
  protected MessagingExceptionHandler messagingExceptionHandler;
  private StreamingManager streamingManager;

  AbstractMessageProcessorChain(String name, List<Processor> processors) {
    this.name = name;
    this.processors = processors;
  }

  @Override
  public Event process(Event event) throws MuleException {
    return processToApply(event, this);
  }

  @Override
  public Publisher<Event> apply(Publisher<Event> publisher) {
    List<BiFunction<Processor, ReactiveProcessor, ReactiveProcessor>> interceptors = resolveInterceptors();
    Flux<Event> stream = from(publisher);
    for (Processor processor : getProcessorsToExecute()) {
      // Perform assembly for processor chain by transforming the existing publisher with a publisher function for each processor
      // along with the interceptors that decorate it.
      stream = stream.transform(applyInterceptors(interceptors, processor));
    }
    return stream;
  }

  private ReactiveProcessor applyInterceptors(List<BiFunction<Processor, ReactiveProcessor, ReactiveProcessor>> interceptorsToBeExecuted,
                                              Processor processor) {
    ReactiveProcessor interceptorWrapperProcessorFunction = processor;
    // Take processor publisher function itself and transform it by applying interceptor transformations onto it.
    for (BiFunction<Processor, ReactiveProcessor, ReactiveProcessor> interceptor : interceptorsToBeExecuted) {
      interceptorWrapperProcessorFunction = interceptor.apply(processor, interceptorWrapperProcessorFunction);
    }
    return interceptorWrapperProcessorFunction;
  }

  private List<BiFunction<Processor, ReactiveProcessor, ReactiveProcessor>> resolveInterceptors() {
    List<BiFunction<Processor, ReactiveProcessor, ReactiveProcessor>> interceptors =
        new ArrayList<>();

    // #1 Update MessagingException with failing processor if required, create Error and set error context.
    interceptors.add((processor, next) -> stream -> from(stream)
        .transform(next)
        .onErrorMap(MessagingException.class, updateMessagingException(processor)));

    // #2 Update ThreadLocal event before processor execution once on processor thread.
    interceptors.add((processor, next) -> stream -> from(stream)
        .doOnNext(event -> setCurrentEvent(event))
        .transform(next));

    // #3 Apply processing strategy. This is done here to ensure notifications and interceptors do not execute on async processor
    // threads which may be limited to avoid deadlocks.
    // Use anonymous ReactiveProcessor to apply processing strategy to processor + previous interceptors
    // while using the processing type of the processor itself.
    if (flowConstruct instanceof Pipeline && ((Pipeline) flowConstruct).getProcessingStrategy() != null) {
      interceptors
          .add((processor, next) -> ((Pipeline) flowConstruct).getProcessingStrategy().onProcessor(new ReactiveProcessor() {

            @Override
            public Publisher<Event> apply(Publisher<Event> eventPublisher) {
              return next.apply(eventPublisher);
            }

            @Override
            public ProcessingType getProcessingType() {
              return processor.getProcessingType();
            }
          }));
    }

    // #4 Update ThreadLocal event after processor execution once back on flow thread.
    interceptors.add((processor, next) -> stream -> from(stream)
        .transform(next)
        .doOnNext(result -> setCurrentEvent(result)));

    // #5 Fire MessageProcessor notifications before and after processor execution.
    interceptors.add((processor, next) -> stream -> from(stream)
        .doOnNext(preNotification(processor))
        .transform(next)
        .doOnNext(postNotification(processor))
        .doOnError(MessagingException.class, errorNotification(processor)));

    // #6 If the processor returns a CursorProvider, then have the StreamingManager manage it
    interceptors.add((processor, next) -> stream -> from(stream)
        .transform(next)
        .map(result -> {
          Object payload = result.getMessage().getPayload().getValue();
          if (payload instanceof CursorProvider) {
            Message message = Message.builder(result.getMessage()).payload(
                                                                           streamingManager.manage((CursorProvider) payload,
                                                                                                   result))
                .build();
            result = Event.builder(result).message(message).build();
          }

          return result;
        }));

    // #7 Apply processor interceptors.
    muleContext.getProcessorInterceptorManager().getInterceptorFactories().stream()
        .forEach(interceptorFactory -> {
          ReactiveInterceptorAdapter reactiveInterceptorAdapter = new ReactiveInterceptorAdapter(interceptorFactory);
          reactiveInterceptorAdapter.setFlowConstruct(flowConstruct);
          interceptors.add(0, reactiveInterceptorAdapter);
        });


    // #8 Handle errors that occur during Processor execution. This is done outside to any scheduling to ensure errors in
    // scheduling such as RejectedExecutionException's can be handled cleanly
    interceptors.add((processor, next) -> stream -> from(stream).concatMap(event -> just(event)
        .transform(next)
        .onErrorResume(RejectedExecutionException.class,
                       throwable -> {
                         from(event.getContext()
                             .error(updateMessagingExceptionWithError(new MessagingException(event, throwable, processor),
                                                                      processor, flowConstruct),
                                    getMessagingExceptionHandler())).subscribe();
                         return empty();
                       })
        .onErrorResume(MessagingException.class,
                       throwable -> {
                         from(event.getContext().error(throwable, getMessagingExceptionHandler())).subscribe();
                         return empty();
                       })));

    return interceptors;
  }

  private Function<MessagingException, MessagingException> updateMessagingException(Processor processor) {
    return exception -> ExceptionUtils.updateMessagingException(LOGGER, processor, exception, muleContext.getErrorTypeLocator(),
                                                                muleContext.getErrorTypeRepository(), flowConstruct, muleContext);
  }

  private Consumer<Event> preNotification(Processor processor) {
    return event -> {
      if (event.isNotificationsEnabled()) {
        fireNotification(muleContext.getNotificationManager(), flowConstruct, event, processor, null,
                         MESSAGE_PROCESSOR_PRE_INVOKE);
      }
    };
  }

  private Consumer<Event> postNotification(Processor processor) {
    return event -> {
      if (event.isNotificationsEnabled()) {
        fireNotification(muleContext.getNotificationManager(), flowConstruct, event, processor, null,
                         MESSAGE_PROCESSOR_POST_INVOKE);

      }
    };
  }

  private Consumer<MessagingException> errorNotification(Processor processor) {
    return exception -> {
      if (exception.getEvent().isNotificationsEnabled()) {
        fireNotification(muleContext.getNotificationManager(), flowConstruct, exception.getEvent(), processor, exception,
                         MESSAGE_PROCESSOR_POST_INVOKE);
      }
    };
  }

  private void fireNotification(ServerNotificationManager serverNotificationManager, FlowConstruct flowConstruct,
                                Event event, Processor processor, MessagingException exceptionThrown,
                                int action) {
    if (serverNotificationManager != null
        && serverNotificationManager.isNotificationEnabled(MessageProcessorNotification.class)) {

      if (processor instanceof AnnotatedObject
          && ((AnnotatedObject) processor).getLocation() != null) {
        serverNotificationManager
            .fireNotification(MessageProcessorNotification.createFrom(event, flowConstruct, processor, exceptionThrown, action));
      }
    }
  }

  protected List<Processor> getProcessorsToExecute() {
    return processors;
  }

  @Override
  public String toString() {
    StringBuilder string = new StringBuilder();
    string.append(getClass().getSimpleName());
    if (!isBlank(name)) {
      string.append(String.format(" '%s' ", name));
    }

    Iterator<Processor> mpIterator = processors.iterator();

    final String nl = String.format("%n");

    // TODO have it print the nested structure with indents increasing for nested MPCs
    if (mpIterator.hasNext()) {
      string.append(String.format("%n[ "));
      while (mpIterator.hasNext()) {
        Processor mp = mpIterator.next();
        final String indented = replace(mp.toString(), nl, String.format("%n  "));
        string.append(String.format("%n  %s", indented));
        if (mpIterator.hasNext()) {
          string.append(", ");
        }
      }
      string.append(String.format("%n]"));
    }

    return string.toString();
  }

  @Override
  public List<Processor> getMessageProcessors() {
    return processors;
  }

  protected List<Processor> getMessageProcessorsForLifecycle() {
    return processors;
  }

  @Override
  public void setMessagingExceptionHandler(MessagingExceptionHandler messagingExceptionHandler) {
    this.messagingExceptionHandler = messagingExceptionHandler;
    for (Processor processor : processors) {
      if (processor instanceof MessagingExceptionHandlerAware) {
        ((MessagingExceptionHandlerAware) processor).setMessagingExceptionHandler(messagingExceptionHandler);
      }
    }
  }

  public MessagingExceptionHandler getMessagingExceptionHandler() {
    return messagingExceptionHandler != null ? messagingExceptionHandler
        : flowConstruct != null ? flowConstruct.getExceptionListener() : NULL_ERROR_HANDLER;
  }

  @Override
  public void setMuleContext(MuleContext muleContext) {
    this.muleContext = muleContext;
    setMuleContextIfNeeded(getMessageProcessorsForLifecycle(), muleContext);
  }

  @Override
  public void setFlowConstruct(FlowConstruct flowConstruct) {
    this.flowConstruct = flowConstruct;
    setFlowConstructIfNeeded(getMessageProcessorsForLifecycle(), flowConstruct);
  }

  @Override
  public void initialise() throws InitialisationException {
    try {
      streamingManager = muleContext.getRegistry().lookupObject(StreamingManager.class);
    } catch (RegistrationException e) {
      throw new InitialisationException(e, this);
    }
    initialiseIfNeeded(getMessageProcessorsForLifecycle(), true, muleContext);
  }

  @Override
  public void start() throws MuleException {
    List<Processor> startedProcessors = new ArrayList<>();
    try {
      for (Processor processor : getMessageProcessorsForLifecycle()) {
        if (processor instanceof Startable) {
          ((Startable) processor).start();
          startedProcessors.add(processor);
        }
      }
    } catch (MuleException e) {
      stopIfNeeded(getMessageProcessorsForLifecycle());
      throw e;
    }
  }

  @Override
  public void stop() throws MuleException {
    stopIfNeeded(getMessageProcessorsForLifecycle());
  }

  @Override
  public void dispose() {
    disposeIfNeeded(getMessageProcessorsForLifecycle(), LOGGER);
  }

}
