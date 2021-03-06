/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.processor;

import static java.util.Collections.singletonList;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.setFlowConstructIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.stopIfNeeded;
import static org.mule.runtime.core.api.processor.MessageProcessors.newChain;
import static org.mule.runtime.core.api.processor.MessageProcessors.processWithChildContext;
import static org.mule.runtime.core.api.transaction.TransactionConfig.ACTION_INDIFFERENT;
import static org.mule.runtime.core.api.transaction.TransactionCoordination.isTransactionActive;
import static org.mule.runtime.core.api.config.i18n.CoreMessages.errorInvokingMessageProcessorWithinTransaction;
import static org.mule.runtime.core.api.execution.TransactionalErrorHandlingExecutionTemplate.createScopeExecutionTemplate;
import static reactor.core.publisher.Flux.from;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.core.api.DefaultMuleException;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.core.api.exception.MessagingExceptionHandler;
import org.mule.runtime.core.api.execution.ExecutionCallback;
import org.mule.runtime.core.api.execution.ExecutionTemplate;
import org.mule.runtime.core.api.processor.MessageProcessorChain;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.transaction.MuleTransactionConfig;
import org.mule.runtime.core.api.transaction.TransactionConfig;

import java.util.List;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the invocation of a list of nested processors {@link org.mule.runtime.core.api.processor.Processor} with a transaction.
 * If the {@link org.mule.runtime.core.api.transaction.TransactionConfig} is null then no transaction is used and the next
 * {@link org.mule.runtime.core.api.processor.Processor} is invoked directly.
 */
public class TryMessageProcessor extends AbstractMessageProcessorOwner implements Processor {

  private static final Logger LOGGER = LoggerFactory.getLogger(TryMessageProcessor.class);

  protected MessageProcessorChain nestedChain;
  protected MuleTransactionConfig transactionConfig;

  @Override
  public Event process(final Event event) throws MuleException {
    if (nestedChain == null) {
      return event;
    } else {
      ExecutionTemplate<Event> executionTemplate =
          createScopeExecutionTemplate(muleContext, flowConstruct, transactionConfig, messagingExceptionHandler);
      ExecutionCallback<Event> processingCallback = () -> nestedChain.process(event);

      try {
        return executionTemplate.execute(processingCallback);
      } catch (MuleException e) {
        throw e;
      } catch (Exception e) {
        throw new DefaultMuleException(errorInvokingMessageProcessorWithinTransaction(nestedChain, transactionConfig), e);
      }
    }
  }

  @Override
  public Publisher<Event> apply(Publisher<Event> publisher) {
    if (nestedChain == null) {
      return publisher;
    } else if (isTransactionActive() || transactionConfig.getAction() != ACTION_INDIFFERENT) {
      return Processor.super.apply(publisher);
    } else {
      return from(publisher).flatMap(event -> processWithChildContext(event, p -> from(p).transform(nestedChain)));
    }
  }

  /**
   * Configures the {@link MessagingExceptionHandler} that should be used to handle any errors that occur in this scope.
   *
   * @param exceptionListener the {@link MessagingExceptionHandler} to be used.
   */
  public void setExceptionListener(MessagingExceptionHandler exceptionListener) {
    this.messagingExceptionHandler = exceptionListener;
  }

  /**
   * Configures the {@link TransactionConfig} that that defines the transactional behaviour of this scope.
   *
   * @param transactionConfig the {@link TransactionConfig} to be used.
   */
  public void setTransactionConfig(MuleTransactionConfig transactionConfig) {
    this.transactionConfig = transactionConfig;
  }

  /**
   * Obtain the {@link TransactionConfig} configured that defines transactional behaviour of this scope.
   *
   * @return the configured {@link TransactionConfig}.
   */
  public MuleTransactionConfig getTransactionConfig() {
    return transactionConfig;
  }

  /**
   * Configure the nested {@link Processor}'s that error handling and transactional behaviour should be applied to.
   * 
   * @param processors
   */
  public void setMessageProcessors(List<Processor> processors) {
    this.nestedChain = newChain(processors);
  }

  @Override
  public void initialise() throws InitialisationException {
    if (messagingExceptionHandler == null) {
      messagingExceptionHandler = muleContext.getDefaultErrorHandler();
    }
    setFlowConstructIfNeeded(messagingExceptionHandler, flowConstruct);
    initialiseIfNeeded(messagingExceptionHandler, true, muleContext);
    super.initialise();
  }

  @Override
  public void dispose() {
    disposeIfNeeded(messagingExceptionHandler, LOGGER);
    super.dispose();
  }

  @Override
  public void start() throws MuleException {
    startIfNeeded(messagingExceptionHandler);
    super.start();
  }

  @Override
  public void stop() throws MuleException {
    stopIfNeeded(messagingExceptionHandler);
    super.stop();
  }

  protected List<Processor> getOwnedMessageProcessors() {
    return singletonList(nestedChain);
  }

  @Override
  public void setMessagingExceptionHandler(MessagingExceptionHandler messagingExceptionHandler) {
    // Ignore so that parent handler is not propagated and instead the exception listener configured for this scope is used.
  }

}
