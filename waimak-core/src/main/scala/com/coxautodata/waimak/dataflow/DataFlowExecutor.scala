package com.coxautodata.waimak.dataflow

import com.coxautodata.waimak.log.Logging

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/**
  * Created by Alexei Perelighin on 11/01/18.
  */
trait DataFlowExecutor extends Logging {

  /**
    * Executes as many actions as possible with the given DAG, stops when no more actions can be executed.
    *
    * @param dataFlow                 initial state with actions to execute and set inputs from previous actions
    * @param errorOnUnexecutedActions whether to throw an exception if some actions on the flow did not execute.
    *                                 Default is true
    * @return (Seq[EXECUTED ACTIONS], FINAL STATE). Final state does not contain the executed actions and the outputs
    *         of the executed actions are now in the inputs
    */
  def execute(dataFlow: DataFlow, errorOnUnexecutedActions: Boolean = true): (Seq[DataFlowAction], DataFlow) = {

    val (executedActions, finalFlow) = dataFlow
      .prepareForExecution()
      .map(loopExecution(_, initActionScheduler(), Seq.empty))
      .flatMap { executionResults: (ActionScheduler, Try[(Seq[DataFlowAction], DataFlow)]) =>
        executionResults._1.shutDown() match {
          case Failure(e) => throw new DataFlowException("Problem shutting down execution pools", e)
          case _ => logDebug("Execution pools were shutdown ok.")
        }
        executionResults._2
      }.get

    finalFlow
      .actions
      .map(_.logLabel)
      .reduceLeftOption((z, a) => s"$z\n$a")
      .foreach {
        case a if errorOnUnexecutedActions =>
          throw new DataFlowException(
            s"There were actions in the flow that did not run. If this was intentional you can allow unexecuted actions " +
              s"by setting the flag [errorOnUnexecutedActions=false] when calling the execute method.\n" +
              s"The actions that did not run were:\n$a"
          )
        case a => logWarning(s"The following actions did not run:\n$a")
      }

    (executedActions, finalFlow)

  }


  /**
    * Used to report events on the flow.
    */
  def flowReporter: FlowReporter

  /**
    * A complex data flow has lots of parallel, diverging and converging actions, lots of the actions could be started
    * in parallel, but certain actions if started earlier could lead to quicker end to end execution of all of the
    * flows and various strategies could lead to it. This strategy will always be applied to a set of actions to schedule
    * regardless of the scheduler implementation.
    *
    * @return
    */
  def priorityStrategy: DFExecutorPriorityStrategies.priorityStrategy

  /**
    * Action scheduler used to run actions
    *
    * @return
    */
  def initActionScheduler(): ActionScheduler

  @tailrec
  private def loopExecution(currentFlow: DataFlow
                            , actionScheduler: ActionScheduler
                            , successfulActions: Seq[DataFlowAction]
                           ): (ActionScheduler, Try[(Seq[DataFlowAction], DataFlow)]) = {
    toSchedule(currentFlow, actionScheduler) match {
      case None if !actionScheduler.hasRunningActions => //No more actions to schedule and none are running => finish data flow execution
        logInfo(s"Flow exit successfulActions: ${successfulActions.mkString("[", "", "]")} remaining: ${currentFlow.actions.mkString("[", ",", "]")}")
        (actionScheduler, Success((successfulActions, currentFlow)))
      case None => {
        actionScheduler.waitToFinish(currentFlow.flowContext, flowReporter) match { // nothing to schedule, in order to continue need to wait for some running actions to finish to unlock other actions
          case Success((newScheduler, actionResults)) => {
            processActionResults(actionResults, currentFlow, successfulActions) match {
              case Success((newFlow, newSuccessfulActions)) => loopExecution(newFlow, newScheduler, newSuccessfulActions)
              case Failure(e) => (actionScheduler, Failure(e))
            }
          }
          case Failure(e) => (actionScheduler, Failure(e))
        }
      }
      case Some((executionPoolName, action)) => {
        //submit action for execution aka to schedule
        val inputEntities: DataFlowEntities = {
          currentFlow.inputs.filterLabels(action.inputLabels)
        }
        loopExecution(currentFlow, actionScheduler.schedule(executionPoolName, action, inputEntities, currentFlow.flowContext, flowReporter), successfulActions)
      }
    }
  }

  /**
    * Determines which execution pool to schedule in and an action to schedule into it.
    * Decision depends on:
    * 1) slots available in the pools
    * 2) actions available for the pools with slots
    * 3) priority strategy that will select and change the order of the available actions
    *
    * @param currentFlow
    * @param actionScheduler
    * @return (Pool into which to schedule, Action to schedule)
    */
  protected[dataflow] def toSchedule(currentFlow: DataFlow, actionScheduler: ActionScheduler): Option[(String, DataFlowAction)] = {
    val toSchedule: Option[(String, DataFlowAction)] = actionScheduler
      .availableExecutionPools()
      .flatMap(executionPoolNames => priorityStrategy(actionScheduler.dropRunning(executionPoolNames, currentFlow.nextRunnable(executionPoolNames)))
        .headOption.map(actionToSchedule => (currentFlow.schedulingMeta.executionPoolName(actionToSchedule), actionToSchedule))
      )
    toSchedule
  }

  /**
    * Marks actions as processed in the data flow and if all were successful return new state of the data flow.
    *
    * @param actionResults Success or Failure of multiple actions
    * @param currentFlow   Flow in which to mark actions as successful
    * @param successfulActionsUntilNow
    * @return Success((new state of the flow, appended list of successful actions)), Failure will be returned
    *         if at least one action in the actionResults has failed
    */
  private[dataflow] def processActionResults(actionResults: Seq[(DataFlowAction, Try[ActionResult])]
                                             , currentFlow: DataFlow
                                             , successfulActionsUntilNow: Seq[DataFlowAction]): Try[(DataFlow, Seq[DataFlowAction])] = {
    val (success, failed) = actionResults.partition(_._2.isSuccess)
    val res = success.foldLeft((currentFlow, successfulActionsUntilNow)) { (res, actionRes) =>
      val action = actionRes._1
      val nextFlow = res._1.executed(action, actionRes._2.get)
      (nextFlow, res._2 :+ action)
    }
    if (failed.isEmpty) {
      Success(res)
    } else {
      failed.foreach { t =>
        // TODO: maybe add to flowReporter info about failed actions
        logError("Failed Action " + t._1.logLabel + " " + t._2.failed)
      }
      failed.head._2.asInstanceOf[Try[(DataFlow, Seq[DataFlowAction])]]
      Failure(new DataFlowException(s"Exception performing action: ${failed.head._1.logLabel}", failed.head._2.failed.get))
    }
  }

}
