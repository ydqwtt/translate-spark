/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler

import java.io.NotSerializableException
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, Map, Stack}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import akka.actor._
import akka.actor.SupervisorStrategy.Stop
import akka.pattern.ask
import akka.util.Timeout

import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.partial.{ApproximateActionListener, ApproximateEvaluator, PartialResult}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage._
import org.apache.spark.util.{CallSite, SystemClock, Clock, Utils}
import org.apache.spark.storage.BlockManagerMessages.BlockManagerHeartbeat

/**
 * The high-level scheduling layer that implements stage-oriented scheduling. It computes a DAG of
 * stages for each job, keeps track of which RDDs and stage outputs are materialized, and finds a
 * minimal schedule to run the job. It then submits stages as TaskSets to an underlying
 * TaskScheduler implementation that runs them on the cluster.
 *
 * In addition to coming up with a DAG of stages, this class also determines the preferred
 * locations to run each task on, based on the current cache status, and passes these to the
 * low-level TaskScheduler. Furthermore, it handles failures due to shuffle output files being
 * lost, in which case old stages may need to be resubmitted. Failures *within* a stage that are
 * not caused by shuffle file loss are handled by the TaskScheduler, which will retry each task
 * a small number of times before cancelling the whole stage.
 *
 * DAGScheduler是一个面向Stage的高级调度器（逻辑调度器）。对于每一个Job，DAGScheduler会计算出一张基于Stage的DAG图，
 * 跟踪所有的RDD，物化Stage的输出结果，并需找一个最小化调度来运行该Job。DAGScheduler随后会将这些Stage转换为TaskSet，
 * 并提交为底层的TaskSceduler，后者在Cluster上执行这些task
 *
 * 除了生成DAG图，DAGScheduler还要为每个将要执行的task确定最优位置（本地性），并将这些信息一并提交给TaskSchedule。
 * 在容错方面，DAGScheduler还负责因shuffle文件丢失造成的错误，这种情况下，原有的stage可能需要重新提交。对于stage
 * 内部的错误（不是因shuffle文件丢失造成的），由TaskScheduler来处理，TaskScheduler可能会重试几次后取消整个stage
 *
 **   基本概念
 **   Task任务 ：单个分区数据集上的最小处理流程单
 **   TaskSet任务集：一组关联的，但是互相之间没有Shuffle依赖关系的任务所组成的任务集
 **   Stage调度阶段：一个任务集所对应的调度阶段
 **   Job作业：一次RDD Action生成的一个或多个Stage所组成的一次计算作业
 **   DAGScheduler内部维护了各种 task / stage / job之间的映射关系表
 *
 *  DAGSchedulerActorSupervisor 负责监控策略及创建DAGSchedulerEventProcessActor
 *  DAGSchedulerEventProcessActor 负责消息循环，并为内部提供统一的作业调度接口
 *  （用DAGSchedulerEventProcessActor解决了Event处理中的并发、同步、异步等问题，
 *    没有所用锁机制，提高了效率，很漂亮)
 */
private[spark]
class DAGScheduler(
    private[scheduler] val sc: SparkContext,
    private[scheduler] val taskScheduler: TaskScheduler,
    listenerBus: LiveListenerBus,
    mapOutputTracker: MapOutputTrackerMaster,
    blockManagerMaster: BlockManagerMaster,
    env: SparkEnv,
    clock: Clock = SystemClock)
  extends Logging {

  import DAGScheduler._

  def this(sc: SparkContext, taskScheduler: TaskScheduler) = {
    this(
      sc,
      taskScheduler,
      sc.listenerBus,
      sc.env.mapOutputTracker.asInstanceOf[MapOutputTrackerMaster],
      sc.env.blockManager.master,
      sc.env)
  }

  def this(sc: SparkContext) = this(sc, sc.taskScheduler)

  private[scheduler] val nextJobId = new AtomicInteger(0)
  private[scheduler] def numTotalJobs: Int = nextJobId.get()
  private val nextStageId = new AtomicInteger(0)

  private[scheduler] val jobIdToStageIds = new HashMap[Int, HashSet[Int]]
  private[scheduler] val stageIdToStage = new HashMap[Int, Stage]
  private[scheduler] val shuffleToMapStage = new HashMap[Int, Stage]
  private[scheduler] val jobIdToActiveJob = new HashMap[Int, ActiveJob]

  // Stages we need to run whose parents aren't done
  private[scheduler] val waitingStages = new HashSet[Stage]

  // Stages we are running right now
  private[scheduler] val runningStages = new HashSet[Stage]

  // Stages that must be resubmitted due to fetch failures
  private[scheduler] val failedStages = new HashSet[Stage]

  private[scheduler] val activeJobs = new HashSet[ActiveJob]

  // Contains the locations that each RDD's partitions are cached on
  private val cacheLocs = new HashMap[Int, Array[Seq[TaskLocation]]]

  // For tracking failed nodes, we use the MapOutputTracker's epoch number, which is sent with
  // every task. When we detect a node failing, we note the current epoch number and failed
  // executor, increment it for new tasks, and use this to ignore stray ShuffleMapTask results.
  // 为了跟踪失败的节点，我们使用了MapOutputTracker中的epoch时间戳。当探测到一个节点失败时，我们记录下
  // 当前epoch值和失败的Executor，将epoch+1作为新任务的epoch，从而能够甄别一个ShuffleMapTask返回的结果
  // 是否该丢弃。（注：该map的key为ExecutorId, Value为时间戳）
  // TODO: Garbage collect information about failure epochs when we know there are no more
  //       stray messages to detect.
  private val failedEpoch = new HashMap[String, Long]

  private val dagSchedulerActorSupervisor =
    env.actorSystem.actorOf(Props(new DAGSchedulerActorSupervisor(this)))

  // A closure serializer that we reuse.
  // This is only safe because DAGScheduler runs in a single thread.
  // 闭包序列化器 DAGScheduler运行在一个单线程环境中，这样使用不存在安全问题
  private val closureSerializer = SparkEnv.get.closureSerializer.newInstance()

  private[scheduler] var eventProcessActor: ActorRef = _

  /** If enabled, we may run certain actions like take() and first() locally. */
  private val localExecutionEnabled = sc.getConf.getBoolean("spark.localExecution.enabled", false)

  /** If enabled, FetchFailed will not cause stage retry, in order to surface the problem. */
  private val disallowStageRetryForTest = sc.getConf.getBoolean("spark.test.noStageRetry", false)

  private def initializeEventProcessActor() {
    // blocking the thread until supervisor is started, which ensures eventProcessActor is
    // not null before any job is submitted
    // 阻塞当前线程，等待supervisor启动，这样可以确保Job提交时，eventProcessActor not null
    implicit val timeout = Timeout(30 seconds)
    val initEventActorReply =
      dagSchedulerActorSupervisor ? Props(new DAGSchedulerEventProcessActor(this))
    eventProcessActor = Await.result(initEventActorReply, timeout.duration).
      asInstanceOf[ActorRef]
  }

  initializeEventProcessActor()

  // Called by TaskScheduler to report task's starting.
  def taskStarted(task: Task[_], taskInfo: TaskInfo) {
    eventProcessActor ! BeginEvent(task, taskInfo)
  }

  // Called to report that a task has completed and results are being fetched remotely.
  def taskGettingResult(taskInfo: TaskInfo) {
    eventProcessActor ! GettingResultEvent(taskInfo)
  }

  // Called by TaskScheduler to report task completions or failures.
  def taskEnded(
      task: Task[_],
      reason: TaskEndReason,
      result: Any,
      accumUpdates: Map[Long, Any],
      taskInfo: TaskInfo,
      taskMetrics: TaskMetrics) {
    eventProcessActor ! CompletionEvent(task, reason, result, accumUpdates, taskInfo, taskMetrics)
  }

  /**
   * Update metrics for in-progress tasks and let the master know that the BlockManager is still
   * alive. Return true if the driver knows about the given block manager. Otherwise, return false,
   * indicating that the block manager should re-register.
   * 更新进程中的统计数据，让Master知道BlockManager还活着。
   *   返回值为  true 意味着 Master还记得该BlockManager；
   *   返回值为 false 则表明该BlockManager需要重新向Master注册了
   */
  def executorHeartbeatReceived(
      execId: String,
      taskMetrics: Array[(Long, Int, Int, TaskMetrics)], // (taskId, stageId, stateAttempt, metrics)
      blockManagerId: BlockManagerId): Boolean = {
    listenerBus.post(SparkListenerExecutorMetricsUpdate(execId, taskMetrics))
    implicit val timeout = Timeout(600 seconds)

    Await.result(
      blockManagerMaster.driverActor ? BlockManagerHeartbeat(blockManagerId),
      timeout.duration).asInstanceOf[Boolean]
  }

  // Called by TaskScheduler when an executor fails.
  def executorLost(execId: String) {
    eventProcessActor ! ExecutorLost(execId)
  }

  // Called by TaskScheduler when a host is added
  def executorAdded(execId: String, host: String) {
    eventProcessActor ! ExecutorAdded(execId, host)
  }

  // Called by TaskScheduler to cancel an entire TaskSet due to either repeated failures or
  // cancellation of the job itself.
  def taskSetFailed(taskSet: TaskSet, reason: String) {
    eventProcessActor ! TaskSetFailed(taskSet, reason)
  }

  private def getCacheLocs(rdd: RDD[_]): Array[Seq[TaskLocation]] = {
    if (!cacheLocs.contains(rdd.id)) {
      val blockIds = rdd.partitions.indices.map(index => RDDBlockId(rdd.id, index)).toArray[BlockId]
      val locs = BlockManager.blockIdsToBlockManagers(blockIds, env, blockManagerMaster)
      cacheLocs(rdd.id) = blockIds.map { id =>
        locs.getOrElse(id, Nil).map(bm => TaskLocation(bm.host, bm.executorId))
      }
    }
    cacheLocs(rdd.id)
  }

  private def clearCacheLocs() {
    cacheLocs.clear()
  }

  /**
   * Get or create a shuffle map stage for the given shuffle dependency's map side.
   * The jobId value passed in will be used if the stage doesn't already exist with
   * a lower jobId (jobId always increases across jobs.)
   */
  private def getShuffleMapStage(shuffleDep: ShuffleDependency[_, _, _], jobId: Int): Stage = {
    shuffleToMapStage.get(shuffleDep.shuffleId) match {
      case Some(stage) => stage
      case None =>
        // We are going to register ancestor shuffle dependencies
        registerShuffleDependencies(shuffleDep, jobId)
        // Then register current shuffleDep
        val stage =
          newOrUsedStage(
            shuffleDep.rdd, shuffleDep.rdd.partitions.size, shuffleDep, jobId,
            shuffleDep.rdd.creationSite)
        shuffleToMapStage(shuffleDep.shuffleId) = stage
 
        stage
    }
  }

  /**
   * Create a Stage -- either directly for use as a result stage, or as part of the (re)-creation
   * of a shuffle map stage in newOrUsedStage.  The stage will be associated with the provided
   * jobId. Production of shuffle map stages should always use newOrUsedStage, not newStage
   * directly.
   * 创建一个Stage -- NewStage可以用来创建一个ResultStage，或者是在newOrUsedStage方法中用来创建一个
   * ShuffleMapStage. 不能用newStage直接创建一个ShuffleMapStage（应该使用newOrUsedStage）
   *
   */
  private def newStage(
      rdd: RDD[_],
      numTasks: Int,
      shuffleDep: Option[ShuffleDependency[_, _, _]],
      jobId: Int,
      callSite: CallSite)
    : Stage =
  {
    val parentStages = getParentStages(rdd, jobId)
    val id = nextStageId.getAndIncrement()
    val stage = new Stage(id, rdd, numTasks, shuffleDep, parentStages, jobId, callSite)
    stageIdToStage(id) = stage
    updateJobIdStageIdMaps(jobId, stage)
    stage
  }

  /**
   * Create a shuffle map Stage for the given RDD.  The stage will also be associated with the
   * provided jobId.  If a stage for the shuffleId existed previously so that the shuffleId is
   * present in the MapOutputTracker, then the number and location of available outputs are
   * recovered from the MapOutputTracker
   */
  private def newOrUsedStage(
      rdd: RDD[_],
      numTasks: Int,
      shuffleDep: ShuffleDependency[_, _, _],
      jobId: Int,
      callSite: CallSite)
    : Stage =
  {
    val stage = newStage(rdd, numTasks, Some(shuffleDep), jobId, callSite)
    if (mapOutputTracker.containsShuffle(shuffleDep.shuffleId)) {
      val serLocs = mapOutputTracker.getSerializedMapOutputStatuses(shuffleDep.shuffleId)
      val locs = MapOutputTracker.deserializeMapStatuses(serLocs)
      for (i <- 0 until locs.size) {
        stage.outputLocs(i) = Option(locs(i)).toList   // locs(i) will be null if missing
      }
      stage.numAvailableOutputs = locs.count(_ != null)
    } else {
      // Kind of ugly: need to register RDDs with the cache and map output tracker here
      // since we can't do it in the RDD constructor because # of partitions is unknown
      logInfo("Registering RDD " + rdd.id + " (" + rdd.getCreationSite + ")")
      mapOutputTracker.registerShuffle(shuffleDep.shuffleId, rdd.partitions.size)
    }
    stage
  }

  /**
   * Get or create the list of parent stages for a given RDD. The stages will be assigned the
   * provided jobId if they haven't already been created with a lower jobId.
   * 创建或者获取一个RDD的 parent stage列表，并为JobId赋值（如果JobId为空）
   */
  /**
   * add by yay(598775508) at 2015/1/13-13:57
   * 生成RDD的parent Stage。每遇到一个ShuffleDependency，就生成一个Stage
   */
  private def getParentStages(rdd: RDD[_], jobId: Int): List[Stage] = {
    // TODO 为啥是HashSet而不是List，没有是、次序问题吗？
    //回答上面的问题，一个Stage的parentStages之间肯定是没有依赖的，也就是说parentStages是可以并行计算的，自然没有顺序问题
    val parents = new HashSet[Stage]
    //存储已经被访问到得RDD
    val visited = new HashSet[RDD[_]]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    //手动维护一个stack防止因为递归访问导致栈溢出
    //存储需要被处理的RDD。Stack中得RDD都需要被处理。
    val waitingForVisit = new Stack[RDD[_]]
    def visit(r: RDD[_]) {
      if (!visited(r)) {
        visited += r
        // Kind of ugly: need to register RDDs with the cache here since
        // we can't do it in its constructor because # of partitions is unknown
        // 只有依赖类型为ShuffleDependency的，才有资格成为parent stage
        // 并通过getShuffleMapStage计算出来
        for (dep <- r.dependencies) {
          dep match {
            case shufDep: ShuffleDependency[_, _, _] =>
              parents += getShuffleMapStage(shufDep, jobId)
            case _ =>
              //不是ShuffleDependency，那么就属于同一个Stage
              waitingForVisit.push(dep.rdd)
          }
        }
      }
    }
    waitingForVisit.push(rdd)
    while (!waitingForVisit.isEmpty) {
      visit(waitingForVisit.pop())
    }
    parents.toList
  }

  // Find ancestor missing shuffle dependencies and register into shuffleToMapStage
  private def registerShuffleDependencies(shuffleDep: ShuffleDependency[_, _, _], jobId: Int) = {
    val parentsWithNoMapStage = getAncestorShuffleDependencies(shuffleDep.rdd)
    while (!parentsWithNoMapStage.isEmpty) {
      val currentShufDep = parentsWithNoMapStage.pop()
      val stage =
        newOrUsedStage(
          currentShufDep.rdd, currentShufDep.rdd.partitions.size, currentShufDep, jobId,
          currentShufDep.rdd.creationSite)
      shuffleToMapStage(currentShufDep.shuffleId) = stage
    }
  }

  // Find ancestor shuffle dependencies that are not registered in shuffleToMapStage yet
  private def getAncestorShuffleDependencies(rdd: RDD[_]): Stack[ShuffleDependency[_, _, _]] = {
    val parents = new Stack[ShuffleDependency[_, _, _]]
    val visited = new HashSet[RDD[_]]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    val waitingForVisit = new Stack[RDD[_]]
    def visit(r: RDD[_]) {
      if (!visited(r)) {
        visited += r
        for (dep <- r.dependencies) {
          dep match {
            case shufDep: ShuffleDependency[_, _, _] =>
              if (!shuffleToMapStage.contains(shufDep.shuffleId)) {
                parents.push(shufDep)
              }

              waitingForVisit.push(shufDep.rdd)
            case _ =>
              waitingForVisit.push(dep.rdd)
          }
        }
      }
    }

    waitingForVisit.push(rdd)
    while (!waitingForVisit.isEmpty) {
      visit(waitingForVisit.pop())
    }
    parents
  }

  /** 查询MissingParentStages
   * 以ShuffleDependency为划分依据，
   */
  private def getMissingParentStages(stage: Stage): List[Stage] = {
    val missing = new HashSet[Stage]
    val visited = new HashSet[RDD[_]]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    val waitingForVisit = new Stack[RDD[_]]
    def visit(rdd: RDD[_]) {
      if (!visited(rdd)) {
        visited += rdd
        if (getCacheLocs(rdd).contains(Nil)) {
          for (dep <- rdd.dependencies) {
            dep match {
              case shufDep: ShuffleDependency[_, _, _] =>
                val mapStage = getShuffleMapStage(shufDep, stage.jobId)
                if (!mapStage.isAvailable) {
                  // 仅仅查找到最近邻的ShuffleMapStage
                  missing += mapStage
                }
              case narrowDep: NarrowDependency[_] =>
                waitingForVisit.push(narrowDep.rdd)
            }
          }
        }
      }
    }
    waitingForVisit.push(stage.rdd)
    while (!waitingForVisit.isEmpty) {
      visit(waitingForVisit.pop())
    }
    missing.toList
  }

  /**
   * Registers the given jobId among the jobs that need the given stage and
   * all of that stage's ancestors.
   */
  private def updateJobIdStageIdMaps(jobId: Int, stage: Stage) {
    def updateJobIdStageIdMapsList(stages: List[Stage]) {
      if (stages.nonEmpty) {
        val s = stages.head
        s.jobIds += jobId
        jobIdToStageIds.getOrElseUpdate(jobId, new HashSet[Int]()) += s.id
        val parents: List[Stage] = getParentStages(s.rdd, jobId)
        val parentsWithoutThisJobId = parents.filter { ! _.jobIds.contains(jobId) }
        updateJobIdStageIdMapsList(parentsWithoutThisJobId ++ stages.tail)
      }
    }
    updateJobIdStageIdMapsList(List(stage))
  }

  /**
   * Removes state for job and any stages that are not needed by any other job.  Does not
   * handle cancelling tasks or notifying the SparkListener about finished jobs/stages/tasks.
   *
   * @param job The job whose state to cleanup.
   */
  private def cleanupStateForJobAndIndependentStages(job: ActiveJob) {
    val registeredStages = jobIdToStageIds.get(job.jobId)
    if (registeredStages.isEmpty || registeredStages.get.isEmpty) {
      logError("No stages registered for job " + job.jobId)
    } else {
      stageIdToStage.filterKeys(stageId => registeredStages.get.contains(stageId)).foreach {
        case (stageId, stage) =>
          val jobSet = stage.jobIds
          if (!jobSet.contains(job.jobId)) {
            logError(
              "Job %d not registered for stage %d even though that stage was registered for the job"
              .format(job.jobId, stageId))
          } else {
            def removeStage(stageId: Int) {
              // data structures based on Stage
              for (stage <- stageIdToStage.get(stageId)) {
                if (runningStages.contains(stage)) {
                  logDebug("Removing running stage %d".format(stageId))
                  runningStages -= stage
                }
                for ((k, v) <- shuffleToMapStage.find(_._2 == stage)) {
                  shuffleToMapStage.remove(k)
                }
                if (waitingStages.contains(stage)) {
                  logDebug("Removing stage %d from waiting set.".format(stageId))
                  waitingStages -= stage
                }
                if (failedStages.contains(stage)) {
                  logDebug("Removing stage %d from failed set.".format(stageId))
                  failedStages -= stage
                }
              }
              // data structures based on StageId
              stageIdToStage -= stageId
              logDebug("After removal of stage %d, remaining stages = %d"
                .format(stageId, stageIdToStage.size))
            }

            jobSet -= job.jobId
            if (jobSet.isEmpty) { // no other job needs this stage
              removeStage(stageId)
            }
          }
      }
    }
    jobIdToStageIds -= job.jobId
    jobIdToActiveJob -= job.jobId
    activeJobs -= job
    job.finalStage.resultOfJob = None
  }

  /**
   * Submit a job to the job scheduler and get a JobWaiter object back. The JobWaiter object
   * can be used to block until the the job finishes executing or can be used to cancel the job.
   * 向job scheduler提交Job，并返回一个JobWaiter对象，该对象可以用来Block并等待Job完成或者取消该Job
   * 这是DAG调度器提供的一个异步Job提交接口
   */
  def submitJob[T, U](
      rdd: RDD[T],
      func: (TaskContext, Iterator[T]) => U,
      partitions: Seq[Int],
      callSite: CallSite,
      allowLocal: Boolean,
      resultHandler: (Int, U) => Unit,
      properties: Properties = null): JobWaiter[U] =
  {
    // Check to make sure we are not launching a task on a partition that does not exist.
    // 参数有效性验证，确保partitions有效存在
    val maxPartitions = rdd.partitions.length
    partitions.find(p => p >= maxPartitions || p < 0).foreach { p =>
      throw new IllegalArgumentException(
        "Attempting to access a non-existent partition: " + p + ". " +
          "Total number of partitions: " + maxPartitions)
    }

    val jobId = nextJobId.getAndIncrement()
    if (partitions.size == 0) {
      return new JobWaiter[U](this, jobId, 0, resultHandler)
    }

    assert(partitions.size > 0)
    val func2 = func.asInstanceOf[(TaskContext, Iterator[_]) => _]
    // jobwaiter是记录作业成功与失败的数据结构，一个作业的Task数量是和分片的数量一致的，
    // Task成功之后调用resultHandler保存结果。
    val waiter = new JobWaiter(this, jobId, partitions.size, resultHandler)
    eventProcessActor ! JobSubmitted(
      jobId, rdd, func2, partitions.toArray, allowLocal, callSite, waiter, properties)
    waiter
  }

  /**
   * 向job scheduler提交Job，并等待Job执行结果
   * 这是DAG调度器提供的一个同步Job提交接口
   */
  def runJob[T, U: ClassTag](
      rdd: RDD[T],
      func: (TaskContext, Iterator[T]) => U,
      partitions: Seq[Int],
      callSite: CallSite,
      allowLocal: Boolean,
      resultHandler: (Int, U) => Unit,
      properties: Properties = null)
  {
    val start = System.nanoTime
    val waiter = submitJob(rdd, func, partitions, callSite, allowLocal, resultHandler, properties)
    waiter.awaitResult() match {
      case JobSucceeded => {
        logInfo("Job %d finished: %s, took %f s".format
          (waiter.jobId, callSite.shortForm, (System.nanoTime - start) / 1e9))
      }
      case JobFailed(exception: Exception) =>
        logInfo("Job %d failed: %s, took %f s".format
          (waiter.jobId, callSite.shortForm, (System.nanoTime - start) / 1e9))
        throw exception
    }
  }

  def runApproximateJob[T, U, R](
      rdd: RDD[T],
      func: (TaskContext, Iterator[T]) => U,
      evaluator: ApproximateEvaluator[U, R],
      callSite: CallSite,
      timeout: Long,
      properties: Properties = null)
    : PartialResult[R] =
  {
    val listener = new ApproximateActionListener(rdd, func, evaluator, timeout)
    val func2 = func.asInstanceOf[(TaskContext, Iterator[_]) => _]
    val partitions = (0 until rdd.partitions.size).toArray
    val jobId = nextJobId.getAndIncrement()
    eventProcessActor ! JobSubmitted(
      jobId, rdd, func2, partitions, allowLocal = false, callSite, listener, properties)
    listener.awaitResult()    // Will throw an exception if the job fails
  }

  /**
   * Cancel a job that is running or waiting in the queue.
   */
  def cancelJob(jobId: Int) {
    logInfo("Asked to cancel job " + jobId)
    eventProcessActor ! JobCancelled(jobId)
  }

  def cancelJobGroup(groupId: String) {
    logInfo("Asked to cancel job group " + groupId)
    eventProcessActor ! JobGroupCancelled(groupId)
  }

  /**
   * Cancel all jobs that are running or waiting in the queue.
   */
  def cancelAllJobs() {
    eventProcessActor ! AllJobsCancelled
  }

  private[scheduler] def doCancelAllJobs() {
    // Cancel all running jobs.
    runningStages.map(_.jobId).foreach(handleJobCancellation(_,
      reason = "as part of cancellation of all jobs"))
    activeJobs.clear() // These should already be empty by this point,
    jobIdToActiveJob.clear() // but just in case we lost track of some jobs...
    submitWaitingStages()
  }

  /**
   * Cancel all jobs associated with a running or scheduled stage.
   */
  def cancelStage(stageId: Int) {
    eventProcessActor ! StageCancelled(stageId)
  }

  /**
   * Resubmit any failed stages. Ordinarily called after a small amount of time has passed since
   * the last fetch failure.
   * 重新提交失败的stage。 通常在最后一次抓取失败后，过一小段时间再调用该函数
   */
  private[scheduler] def resubmitFailedStages() {
    if (failedStages.size > 0) {
      // Failed stages may be removed by job cancellation, so failed might be empty even if
      // the ResubmitFailedStages event has been scheduled.
      logInfo("Resubmitting failed stages")
      clearCacheLocs()
      val failedStagesCopy = failedStages.toArray
      failedStages.clear()
      for (stage <- failedStagesCopy.sortBy(_.jobId)) {
        submitStage(stage)
      }
    }
    submitWaitingStages()
  }

  /**
   * Check for waiting or failed stages which are now eligible for resubmission.
   * Ordinarily run on every iteration of the event loop.
   * 检查等待或失败的Stages，并重新提交合格的Stage
   * 通常DAGScheduler完成一次的事件循环以后，也会触发一次从等待和失败列表中扫描并提交就绪Stage的调用过程
   */
  private def submitWaitingStages() {
    // TODO: We might want to run this less often, when we are sure that something has become
    // runnable that wasn't before.
    logTrace("Checking for newly runnable parent stages")
    logTrace("running: " + runningStages)
    logTrace("waiting: " + waitingStages)
    logTrace("failed: " + failedStages)
    val waitingStagesCopy = waitingStages.toArray
    waitingStages.clear()
    for (stage <- waitingStagesCopy.sortBy(_.jobId)) {
      submitStage(stage)
    }
  }

  /**
   * Run a job on an RDD locally, assuming it has only a single partition and no dependencies.
   * We run the operation in a separate thread just in case it takes a bunch of time, so that we
   * don't block the DAGScheduler event loop or other concurrent jobs.
   */
  protected def runLocally(job: ActiveJob) {
    logInfo("Computing the requested partition locally")
    new Thread("Local computation of job " + job.jobId) {
      override def run() {
        runLocallyWithinThread(job)
      }
    }.start()
  }

  // Broken out for easier testing in DAGSchedulerSuite.
  protected def runLocallyWithinThread(job: ActiveJob) {
    var jobResult: JobResult = JobSucceeded
    try {
      val rdd = job.finalStage.rdd
      val split = rdd.partitions(job.partitions(0))
      val taskContext =
        new TaskContextImpl(job.finalStage.id, job.partitions(0), 0, true)
      TaskContextHelper.setTaskContext(taskContext)
      try {
        val result = job.func(taskContext, rdd.iterator(split, taskContext))
        job.listener.taskSucceeded(0, result)
      } finally {
        taskContext.markTaskCompleted()
        TaskContextHelper.unset()
      }
    } catch {
      case e: Exception =>
        val exception = new SparkDriverExecutionException(e)
        jobResult = JobFailed(exception)
        job.listener.jobFailed(exception)
      case oom: OutOfMemoryError =>
        val exception = new SparkException("Local job aborted due to out of memory error", oom)
        jobResult = JobFailed(exception)
        job.listener.jobFailed(exception)
    } finally {
      val s = job.finalStage
      // clean up data structures that were populated for a local job,
      // but that won't get cleaned up via the normal paths through
      // completion events or stage abort
      stageIdToStage -= s.id
      jobIdToStageIds -= job.jobId
      listenerBus.post(SparkListenerJobEnd(job.jobId, jobResult))
    }
  }

  /** Finds the earliest-created active job that needs the stage */
  // TODO: Probably should actually find among the active jobs that need this
  // stage the one with the highest priority (highest-priority pool, earliest created).
  // That should take care of at least part of the priority inversion problem with
  // cross-job dependencies.
  private def activeJobForStage(stage: Stage): Option[Int] = {
    val jobsThatUseStage: Array[Int] = stage.jobIds.toArray.sorted
    jobsThatUseStage.find(jobIdToActiveJob.contains)
  }

  private[scheduler] def handleJobGroupCancelled(groupId: String) {
    // Cancel all jobs belonging to this job group.
    // First finds all active jobs with this group id, and then kill stages for them.
    val activeInGroup = activeJobs.filter(activeJob =>
      groupId == activeJob.properties.get(SparkContext.SPARK_JOB_GROUP_ID))
    val jobIds = activeInGroup.map(_.jobId)
    jobIds.foreach(handleJobCancellation(_, "part of cancelled job group %s".format(groupId)))
    submitWaitingStages()
  }

  private[scheduler] def handleBeginEvent(task: Task[_], taskInfo: TaskInfo) {
    // Note that there is a chance that this task is launched after the stage is cancelled.
    // In that case, we wouldn't have the stage anymore in stageIdToStage.
    val stageAttemptId = stageIdToStage.get(task.stageId).map(_.latestInfo.attemptId).getOrElse(-1)
    listenerBus.post(SparkListenerTaskStart(task.stageId, stageAttemptId, taskInfo))
    submitWaitingStages()
  }

  private[scheduler] def handleTaskSetFailed(taskSet: TaskSet, reason: String) {
    stageIdToStage.get(taskSet.stageId).foreach {abortStage(_, reason) }
    submitWaitingStages()
  }

  private[scheduler] def cleanUpAfterSchedulerStop() {
    for (job <- activeJobs) {
      val error = new SparkException("Job cancelled because SparkContext was shut down")
      job.listener.jobFailed(error)
      // Tell the listeners that all of the running stages have ended.  Don't bother
      // cancelling the stages because if the DAG scheduler is stopped, the entire application
      // is in the process of getting stopped.
      val stageFailedMessage = "Stage cancelled because SparkContext was shut down"
      runningStages.foreach { stage =>
        stage.latestInfo.stageFailed(stageFailedMessage)
        listenerBus.post(SparkListenerStageCompleted(stage.latestInfo))
      }
      listenerBus.post(SparkListenerJobEnd(job.jobId, JobFailed(error)))
    }
  }

  private[scheduler] def handleGetTaskResult(taskInfo: TaskInfo) {
    listenerBus.post(SparkListenerTaskGettingResult(taskInfo))
    submitWaitingStages()
  }

  /** 当某个操作触发计算，向DAGScheduler提交作业时，DAGScheduler需要从RDD依赖链最末端的RDD出发，
    * 遍历整个RDD依赖链，划分Stage任务阶段，并决定各个Stage之间的依赖关系。
    * Stage的划分是以ShuffleDependency为依据的，也就是说当某个RDD的运算需要将数据进行Shuffle时，
    * 这个包含了Shuffle依赖关系的RDD将被用来作为输入信息，构建一个新的Stage，由此为依据划分Stage，
    * 可以确保有依赖关系的数据能够按照正确的顺序得到处理和运算
    *
    * handleJobSubmitted将finalRDD包装为ActiveJob（用于跟踪），并作为finalStage
    * 为生成DAG图准备数据
    */
  private[scheduler] def handleJobSubmitted(jobId: Int,
      finalRDD: RDD[_],
      func: (TaskContext, Iterator[_]) => _,
      partitions: Array[Int],
      allowLocal: Boolean,
      callSite: CallSite,
      listener: JobListener,
      properties: Properties = null)
  {
    var finalStage: Stage = null
    try {
      // New stage creation may throw an exception if, for example, jobs are run on a
      // HadoopRDD whose underlying HDFS files have been deleted.
      finalStage = newStage(finalRDD, partitions.size, None, jobId, callSite)
    } catch {
      case e: Exception =>
        logWarning("Creating new stage failed due to exception - job: " + jobId, e)
        listener.jobFailed(e)
        return
    }
    // finalStage被包装为ActiveJob
    if (finalStage != null) {
      val job = new ActiveJob(jobId, finalStage, func, partitions, callSite, listener, properties)
      clearCacheLocs()
      logInfo("Got job %s (%s) with %d output partitions (allowLocal=%s)".format(
        job.jobId, callSite.shortForm, partitions.length, allowLocal))
      logInfo("Final stage: " + finalStage + "(" + finalStage.name + ")")
      logInfo("Parents of final stage: " + finalStage.parents)
      logInfo("Missing parents: " + getMissingParentStages(finalStage))
      val shouldRunLocally =
        localExecutionEnabled && allowLocal && finalStage.parents.isEmpty && partitions.length == 1
      if (shouldRunLocally) {
        // Compute very short actions like first() or take() with no parent stages locally.
        listenerBus.post(SparkListenerJobStart(job.jobId, Seq.empty, properties))
        runLocally(job)
      } else {
        jobIdToActiveJob(jobId) = job
        activeJobs += job
        finalStage.resultOfJob = Some(job)
        val stageIds = jobIdToStageIds(jobId).toArray
        val stageInfos = stageIds.flatMap(id => stageIdToStage.get(id).map(_.latestInfo))
        listenerBus.post(SparkListenerJobStart(job.jobId, stageInfos, properties))
        submitStage(finalStage)
      }
    }
    //检查waitingStages，并提交可用的Stage
    submitWaitingStages()
  }

  /** Submits stage, but first recursively submits any missing parents.
    * 提交Stage，首先递归提交其parent Stage
    */
  private def submitStage(stage: Stage) {
    val jobId = activeJobForStage(stage)
    if (jobId.isDefined) {
      logDebug("submitStage(" + stage + ")")
      // 如果当前stage不再等待其parent stage的返回，并且 不在运行的状态， 并且 没有已经失败（失败会有重试机制，不会通过这里再次提交）
      if (!waitingStages(stage) && !runningStages(stage) && !failedStages(stage)) {
        val missing = getMissingParentStages(stage).sortBy(_.id)
        logDebug("missing: " + missing)
        // 如果所有的parent stage都已经完成，那么提交该stage所包含的task
        if (missing == Nil) {
          logInfo("Submitting " + stage + " (" + stage.rdd + "), which has no missing parents")
          // 没有父stage，执行这stage的tasks
          submitMissingTasks(stage, jobId.get)
        } else {
          for (parent <- missing) {
            submitStage(parent)
          }
          waitingStages += stage
        }
      }
    } else {
      abortStage(stage, "No active job for stage " + stage.id)
    }
  }

  /** Called when stage's parents are available and we can now do its task.
    * MissingTask (这个名字太费解了) 就是提交一个满足运行提交的Stage */
  private def submitMissingTasks(stage: Stage, jobId: Int) {
    logDebug("submitMissingTasks(" + stage + ")")
    // Get our pending tasks and remember them in our pendingTasks entry
    stage.pendingTasks.clear()

    // First figure out the indexes of partition ids to compute.
    val partitionsToCompute: Seq[Int] = {
      if (stage.isShuffleMap) {
        (0 until stage.numPartitions).filter(id => stage.outputLocs(id) == Nil)
      } else {
        val job = stage.resultOfJob.get
        (0 until job.numPartitions).filter(id => !job.finished(id))
      }
    }

    val properties = if (jobIdToActiveJob.contains(jobId)) {
      jobIdToActiveJob(stage.jobId).properties
    } else {
      // this stage will be assigned to "default" pool
      null
    }

    runningStages += stage
    // SparkListenerStageSubmitted should be posted before testing whether tasks are
    // serializable. If tasks are not serializable, a SparkListenerStageCompleted event
    // will be posted, which should always come after a corresponding SparkListenerStageSubmitted
    // event.
    stage.latestInfo = StageInfo.fromStage(stage, Some(partitionsToCompute.size))
    listenerBus.post(SparkListenerStageSubmitted(stage.latestInfo, properties))

    // TODO: Maybe we can keep the taskBinary in Stage to avoid serializing it multiple times.
    // Broadcasted binary for the task, used to dispatch tasks to executors. Note that we broadcast
    // the serialized copy of the RDD and for each task we will deserialize it, which means each
    // task gets a different copy of the RDD. This provides stronger isolation between tasks that
    // might modify state of objects referenced in their closures. This is necessary in Hadoop
    // where the JobConf/Configuration object is not thread-safe.
    // 将task执行所用到的数据打包并序列化，各个task在实现执行前再反序列化
    var taskBinary: Broadcast[Array[Byte]] = null
    try {
      // For ShuffleMapTask, serialize and broadcast (rdd, shuffleDep).
      // For ResultTask, serialize and broadcast (rdd, func).
      val taskBinaryBytes: Array[Byte] =
        if (stage.isShuffleMap) {
          closureSerializer.serialize((stage.rdd, stage.shuffleDep.get) : AnyRef).array()
        } else {
          closureSerializer.serialize((stage.rdd, stage.resultOfJob.get.func) : AnyRef).array()
        }
      taskBinary = sc.broadcast(taskBinaryBytes)
    } catch {
      // In the case of a failure during serialization, abort the stage.
      case e: NotSerializableException =>
        abortStage(stage, "Task not serializable: " + e.toString)
        runningStages -= stage
        return
      case NonFatal(e) =>
        abortStage(stage, s"Task serialization failed: $e\n${e.getStackTraceString}")
        runningStages -= stage
        return
    }

    val tasks: Seq[Task[_]] = if (stage.isShuffleMap) {
      partitionsToCompute.map { id =>
        val locs = getPreferredLocs(stage.rdd, id)
        val part = stage.rdd.partitions(id)
        new ShuffleMapTask(stage.id, taskBinary, part, locs)
      }
    } else {
      val job = stage.resultOfJob.get
      partitionsToCompute.map { id =>
        val p: Int = job.partitions(id)
        val part = stage.rdd.partitions(p)
        val locs = getPreferredLocs(stage.rdd, p)
        new ResultTask(stage.id, taskBinary, part, locs, id)
      }
    }

    if (tasks.size > 0) {
      // Preemptively serialize a task to make sure it can be serialized. We are catching this
      // exception here because it would be fairly hard to catch the non-serializable exception
      // down the road, where we have several different implementations for local scheduler and
      // cluster schedulers.
      //
      // We've already serialized RDDs and closures in taskBinary, but here we check for all other
      // objects such as Partition.
      try {
        closureSerializer.serialize(tasks.head)
      } catch {
        case e: NotSerializableException =>
          abortStage(stage, "Task not serializable: " + e.toString)
          runningStages -= stage
          return
        case NonFatal(e) => // Other exceptions, such as IllegalArgumentException from Kryo.
          abortStage(stage, s"Task serialization failed: $e\n${e.getStackTraceString}")
          runningStages -= stage
          return
      }

      logInfo("Submitting " + tasks.size + " missing tasks from " + stage + " (" + stage.rdd + ")")
      stage.pendingTasks ++= tasks
      logDebug("New pending tasks: " + stage.pendingTasks)
      taskScheduler.submitTasks(
        new TaskSet(tasks.toArray, stage.id, stage.newAttemptId(), stage.jobId, properties))
      stage.latestInfo.submissionTime = Some(clock.getTime())
    } else {
      // Because we posted SparkListenerStageSubmitted earlier, we should post
      // SparkListenerStageCompleted here in case there are no tasks to run.
      listenerBus.post(SparkListenerStageCompleted(stage.latestInfo))
      logDebug("Stage " + stage + " is actually done; %b %d %d".format(
        stage.isAvailable, stage.numAvailableOutputs, stage.numPartitions))
      runningStages -= stage
    }
  }

  /** Merge updates from a task to our local accumulator values */
  private def updateAccumulators(event: CompletionEvent): Unit = {
    val task = event.task
    val stage = stageIdToStage(task.stageId)
    if (event.accumUpdates != null) {
      try {
        Accumulators.add(event.accumUpdates)
        event.accumUpdates.foreach { case (id, partialValue) =>
          val acc = Accumulators.originals(id).asInstanceOf[Accumulable[Any, Any]]
          // To avoid UI cruft, ignore cases where value wasn't updated
          if (acc.name.isDefined && partialValue != acc.zero) {
            val name = acc.name.get
            val stringPartialValue = Accumulators.stringifyPartialValue(partialValue)
            val stringValue = Accumulators.stringifyValue(acc.value)
            stage.latestInfo.accumulables(id) = AccumulableInfo(id, name, stringValue)
            event.taskInfo.accumulables +=
              AccumulableInfo(id, name, Some(stringPartialValue), stringValue)
          }
        }
      } catch {
        // If we see an exception during accumulator update, just log the
        // error and move on.
        case e: Exception =>
          logError(s"Failed to update accumulators for $task", e)
      }
    }
  }

  /**
   * Responds to a task finishing. This is called inside the event loop so it assumes that it can
   * modify the scheduler's internal state. Use taskEnded() to post a task end event from outside.
   * task完成事件的响应。Event循环中内部调用，用来更新Scheduler的内部状态。 外部通过taskEnded()方法可以发布
   * 一个task结束事件
   */
  private[scheduler] def handleTaskCompletion(event: CompletionEvent) {
    val task = event.task
    val stageId = task.stageId
    val taskType = Utils.getFormattedClassName(task)

    // The success case is dealt with separately below, since we need to compute accumulator
    // updates before posting.
    if (event.reason != Success) {
      val attemptId = stageIdToStage.get(task.stageId).map(_.latestInfo.attemptId).getOrElse(-1)
      listenerBus.post(SparkListenerTaskEnd(stageId, attemptId, taskType, event.reason,
        event.taskInfo, event.taskMetrics))
    }

    if (!stageIdToStage.contains(task.stageId)) {
      // Skip all the actions if the stage has been cancelled.
      return
    }
    val stage = stageIdToStage(task.stageId)

    def markStageAsFinished(stage: Stage, errorMessage: Option[String] = None) = {
      val serviceTime = stage.latestInfo.submissionTime match {
        case Some(t) => "%.03f".format((clock.getTime() - t) / 1000.0)
        case _ => "Unknown"
      }
      if (errorMessage.isEmpty) {
        logInfo("%s (%s) finished in %s s".format(stage, stage.name, serviceTime))
        stage.latestInfo.completionTime = Some(clock.getTime())
      } else {
        stage.latestInfo.stageFailed(errorMessage.get)
        logInfo("%s (%s) failed in %s s".format(stage, stage.name, serviceTime))
      }
      listenerBus.post(SparkListenerStageCompleted(stage.latestInfo))
      runningStages -= stage
    }
    event.reason match {
      case Success =>
        listenerBus.post(SparkListenerTaskEnd(stageId, stage.latestInfo.attemptId, taskType,
          event.reason, event.taskInfo, event.taskMetrics))
        stage.pendingTasks -= task
        task match {
          case rt: ResultTask[_, _] =>
            stage.resultOfJob match {
              case Some(job) =>
                if (!job.finished(rt.outputId)) {
                  updateAccumulators(event)
                  job.finished(rt.outputId) = true
                  job.numFinished += 1
                  // If the whole job has finished, remove it
                  if (job.numFinished == job.numPartitions) {
                    markStageAsFinished(stage)
                    cleanupStateForJobAndIndependentStages(job)
                    listenerBus.post(SparkListenerJobEnd(job.jobId, JobSucceeded))
                  }

                  // taskSucceeded runs some user code that might throw an exception. Make sure
                  // we are resilient against that.
                  try {
                    job.listener.taskSucceeded(rt.outputId, event.result)
                  } catch {
                    case e: Exception =>
                      // TODO: Perhaps we want to mark the stage as failed?
                      job.listener.jobFailed(new SparkDriverExecutionException(e))
                  }
                }
              case None =>
                logInfo("Ignoring result from " + rt + " because its job has finished")
            }

          case smt: ShuffleMapTask =>
            updateAccumulators(event)
            val status = event.result.asInstanceOf[MapStatus]
            val execId = status.location.executorId
            logDebug("ShuffleMapTask finished on " + execId)
            if (failedEpoch.contains(execId) && smt.epoch <= failedEpoch(execId)) {
              logInfo("Ignoring possibly bogus ShuffleMapTask completion from " + execId)
            } else {
              stage.addOutputLoc(smt.partitionId, status)
            }
            if (runningStages.contains(stage) && stage.pendingTasks.isEmpty) {
              markStageAsFinished(stage)
              logInfo("looking for newly runnable stages")
              logInfo("running: " + runningStages)
              logInfo("waiting: " + waitingStages)
              logInfo("failed: " + failedStages)
              if (stage.shuffleDep.isDefined) {
                // We supply true to increment the epoch number here in case this is a
                // recomputation of the map outputs. In that case, some nodes may have cached
                // locations with holes (from when we detected the error) and will need the
                // epoch incremented to refetch them.
                // TODO: Only increment the epoch number if this is not the first time
                //       we registered these map outputs.
                mapOutputTracker.registerMapOutputs(
                  stage.shuffleDep.get.shuffleId,
                  stage.outputLocs.map(list => if (list.isEmpty) null else list.head).toArray,
                  changeEpoch = true)
              }
              clearCacheLocs()
              if (stage.outputLocs.exists(_ == Nil)) {
                // Some tasks had failed; let's resubmit this stage
                // TODO: Lower-level scheduler should also deal with this
                logInfo("Resubmitting " + stage + " (" + stage.name +
                  ") because some of its tasks had failed: " +
                  stage.outputLocs.zipWithIndex.filter(_._1 == Nil).map(_._2).mkString(", "))
                submitStage(stage)
              } else {
                val newlyRunnable = new ArrayBuffer[Stage]
                for (stage <- waitingStages) {
                  logInfo("Missing parents for " + stage + ": " + getMissingParentStages(stage))
                }
                for (stage <- waitingStages if getMissingParentStages(stage) == Nil) {
                  newlyRunnable += stage
                }
                waitingStages --= newlyRunnable
                runningStages ++= newlyRunnable
                for {
                  stage <- newlyRunnable.sortBy(_.id)
                  jobId <- activeJobForStage(stage)
                } {
                  logInfo("Submitting " + stage + " (" + stage.rdd + "), which is now runnable")
                  submitMissingTasks(stage, jobId)
                }
              }
            }
          }

      case Resubmitted =>
        logInfo("Resubmitted " + task + ", so marking it as still running")
        stage.pendingTasks += task

      case FetchFailed(bmAddress, shuffleId, mapId, reduceId, failureMessage) =>
        val failedStage = stageIdToStage(task.stageId)
        val mapStage = shuffleToMapStage(shuffleId)

        // It is likely that we receive multiple FetchFailed for a single stage (because we have
        // multiple tasks running concurrently on different executors). In that case, it is possible
        // the fetch failure has already been handled by the scheduler.
        if (runningStages.contains(failedStage)) {
          logInfo(s"Marking $failedStage (${failedStage.name}) as failed " +
            s"due to a fetch failure from $mapStage (${mapStage.name})")
          markStageAsFinished(failedStage, Some(failureMessage))
          runningStages -= failedStage
        }

        if (disallowStageRetryForTest) {
          abortStage(failedStage, "Fetch failure will not retry stage due to testing config")
        } else if (failedStages.isEmpty && eventProcessActor != null) {
          // Don't schedule an event to resubmit failed stages if failed isn't empty, because
          // in that case the event will already have been scheduled. eventProcessActor may be
          // null during unit tests.
          // TODO: Cancel running tasks in the stage
          import env.actorSystem.dispatcher
          logInfo(s"Resubmitting $mapStage (${mapStage.name}) and " +
            s"$failedStage (${failedStage.name}) due to fetch failure")
          env.actorSystem.scheduler.scheduleOnce(
            RESUBMIT_TIMEOUT, eventProcessActor, ResubmitFailedStages)
        }
        failedStages += failedStage
        failedStages += mapStage
        // Mark the map whose fetch failed as broken in the map stage
        if (mapId != -1) {
          mapStage.removeOutputLoc(mapId, bmAddress)
          mapOutputTracker.unregisterMapOutput(shuffleId, mapId, bmAddress)
        }

        // TODO: mark the executor as failed only if there were lots of fetch failures on it
        if (bmAddress != null) {
          handleExecutorLost(bmAddress.executorId, fetchFailed = true, Some(task.epoch))
        }

      case ExceptionFailure(className, description, stackTrace, fullStackTrace, metrics) =>
        // Do nothing here, left up to the TaskScheduler to decide how to handle user failures

      case TaskResultLost =>
        // Do nothing here; the TaskScheduler handles these failures and resubmits the task.

      case other =>
        // Unrecognized failure - also do nothing. If the task fails repeatedly, the TaskScheduler
        // will abort the job.
    }
    submitWaitingStages()
  }

  /**
   * Responds to an executor being lost. This is called inside the event loop, so it assumes it can
   * modify the scheduler's internal state. Use executorLost() to post a loss event from outside.
   *
   * We will also assume that we've lost all shuffle blocks associated with the executor if the
   * executor serves its own blocks (i.e., we're not using external shuffle) OR a FetchFailed
   * occurred, in which case we presume all shuffle data related to this executor to be lost.
   *
   * Optionally the epoch during which the failure was caught can be passed to avoid allowing
   * stray fetch failures from possibly retriggering the detection of a node as lost.
   *
   * 响应Exexutor丢失。 这个方法只能在事件循环内调用，因此它可以修改调度器内部的状态。事件循环外部可以调用
   * executorLost()方法来提交一个Exexutor消失事件。
   *
   *
   */
  private[scheduler] def handleExecutorLost(
      execId: String,
      fetchFailed: Boolean,
      maybeEpoch: Option[Long] = None) {
    val currentEpoch = maybeEpoch.getOrElse(mapOutputTracker.getEpoch)
    if (!failedEpoch.contains(execId) || failedEpoch(execId) < currentEpoch) {
      failedEpoch(execId) = currentEpoch
      logInfo("Executor lost: %s (epoch %d)".format(execId, currentEpoch))
      blockManagerMaster.removeExecutor(execId)

      if (!env.blockManager.externalShuffleServiceEnabled || fetchFailed) {
        // TODO: This will be really slow if we keep accumulating shuffle map stages
        for ((shuffleId, stage) <- shuffleToMapStage) {
          stage.removeOutputsOnExecutor(execId)
          val locs = stage.outputLocs.map(list => if (list.isEmpty) null else list.head).toArray
          mapOutputTracker.registerMapOutputs(shuffleId, locs, changeEpoch = true)
        }
        if (shuffleToMapStage.isEmpty) {
          mapOutputTracker.incrementEpoch()
        }
        clearCacheLocs()
      }
    } else {
      logDebug("Additional executor lost message for " + execId +
               "(epoch " + currentEpoch + ")")
    }
    submitWaitingStages()
  }

  private[scheduler] def handleExecutorAdded(execId: String, host: String) {
    // remove from failedEpoch(execId) ?
    if (failedEpoch.contains(execId)) {
      logInfo("Host added was in lost list earlier: " + host)
      failedEpoch -= execId
    }
    submitWaitingStages()
  }

  private[scheduler] def handleStageCancellation(stageId: Int) {
    stageIdToStage.get(stageId) match {
      case Some(stage) =>
        val jobsThatUseStage: Array[Int] = stage.jobIds.toArray
        jobsThatUseStage.foreach { jobId =>
          handleJobCancellation(jobId, s"because Stage $stageId was cancelled")
        }
      case None =>
        logInfo("No active jobs to kill for Stage " + stageId)
    }
    submitWaitingStages()
  }

  private[scheduler] def handleJobCancellation(jobId: Int, reason: String = "") {
    if (!jobIdToStageIds.contains(jobId)) {
      logDebug("Trying to cancel unregistered job " + jobId)
    } else {
      failJobAndIndependentStages(
        jobIdToActiveJob(jobId), "Job %d cancelled %s".format(jobId, reason))
    }
    submitWaitingStages()
  }

  /**
   * Aborts all jobs depending on a particular Stage. This is called in response to a task set
   * being canceled by the TaskScheduler. Use taskSetFailed() to inject this event from outside.
   */
  private[scheduler] def abortStage(failedStage: Stage, reason: String) {
    if (!stageIdToStage.contains(failedStage.id)) {
      // Skip all the actions if the stage has been removed.
      return
    }
    val dependentJobs: Seq[ActiveJob] =
      activeJobs.filter(job => stageDependsOn(job.finalStage, failedStage)).toSeq
    failedStage.latestInfo.completionTime = Some(clock.getTime())
    for (job <- dependentJobs) {
      failJobAndIndependentStages(job, s"Job aborted due to stage failure: $reason")
    }
    if (dependentJobs.isEmpty) {
      logInfo("Ignoring failure of " + failedStage + " because all jobs depending on it are done")
    }
  }

  /**
   * Fails a job and all stages that are only used by that job, and cleans up relevant state.
   */
  private def failJobAndIndependentStages(job: ActiveJob, failureReason: String) {
    val error = new SparkException(failureReason)
    var ableToCancelStages = true

    val shouldInterruptThread =
      if (job.properties == null) false
      else job.properties.getProperty(SparkContext.SPARK_JOB_INTERRUPT_ON_CANCEL, "false").toBoolean

    // Cancel all independent, running stages.
    val stages = jobIdToStageIds(job.jobId)
    if (stages.isEmpty) {
      logError("No stages registered for job " + job.jobId)
    }
    stages.foreach { stageId =>
      val jobsForStage: Option[HashSet[Int]] = stageIdToStage.get(stageId).map(_.jobIds)
      if (jobsForStage.isEmpty || !jobsForStage.get.contains(job.jobId)) {
        logError(
          "Job %d not registered for stage %d even though that stage was registered for the job"
            .format(job.jobId, stageId))
      } else if (jobsForStage.get.size == 1) {
        if (!stageIdToStage.contains(stageId)) {
          logError(s"Missing Stage for stage with id $stageId")
        } else {
          // This is the only job that uses this stage, so fail the stage if it is running.
          val stage = stageIdToStage(stageId)
          if (runningStages.contains(stage)) {
            try { // cancelTasks will fail if a SchedulerBackend does not implement killTask
              taskScheduler.cancelTasks(stageId, shouldInterruptThread)
              stage.latestInfo.stageFailed(failureReason)
              listenerBus.post(SparkListenerStageCompleted(stage.latestInfo))
            } catch {
              case e: UnsupportedOperationException =>
                logInfo(s"Could not cancel tasks for stage $stageId", e)
              ableToCancelStages = false
            }
          }
        }
      }
    }

    if (ableToCancelStages) {
      job.listener.jobFailed(error)
      cleanupStateForJobAndIndependentStages(job)
      listenerBus.post(SparkListenerJobEnd(job.jobId, JobFailed(error)))
    }
  }

  /**
   * Return true if one of stage's ancestors is target.
   */
  private def stageDependsOn(stage: Stage, target: Stage): Boolean = {
    if (stage == target) {
      return true
    }
    val visitedRdds = new HashSet[RDD[_]]
    val visitedStages = new HashSet[Stage]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    val waitingForVisit = new Stack[RDD[_]]
    def visit(rdd: RDD[_]) {
      if (!visitedRdds(rdd)) {
        visitedRdds += rdd
        for (dep <- rdd.dependencies) {
          dep match {
            case shufDep: ShuffleDependency[_, _, _] =>
              val mapStage = getShuffleMapStage(shufDep, stage.jobId)
              if (!mapStage.isAvailable) {
                visitedStages += mapStage
                waitingForVisit.push(mapStage.rdd)
              }  // Otherwise there's no need to follow the dependency back
            case narrowDep: NarrowDependency[_] =>
              waitingForVisit.push(narrowDep.rdd)
          }
        }
      }
    }
    waitingForVisit.push(stage.rdd)
    while (!waitingForVisit.isEmpty) {
      visit(waitingForVisit.pop())
    }
    visitedRdds.contains(target.rdd)
  }

  /**
   * Synchronized method that might be called from other threads.
   * @param rdd whose partitions are to be looked at
   * @param partition to lookup locality information for
   * @return list of machines that are preferred by the partition
   */
  private[spark]
  def getPreferredLocs(rdd: RDD[_], partition: Int): Seq[TaskLocation] = synchronized {
    getPreferredLocsInternal(rdd, partition, new HashSet)
  }

  /** Recursive implementation for getPreferredLocs. */
  private def getPreferredLocsInternal(
      rdd: RDD[_],
      partition: Int,
      visited: HashSet[(RDD[_],Int)])
    : Seq[TaskLocation] =
  {
    // If the partition has already been visited, no need to re-visit.
    // This avoids exponential path exploration.  SPARK-695
    if (!visited.add((rdd,partition))) {
      // Nil has already been returned for previously visited partitions.
      return Nil
    }
    // If the partition is cached, return the cache locations
    val cached = getCacheLocs(rdd)(partition)
    if (!cached.isEmpty) {
      return cached
    }
    // If the RDD has some placement preferences (as is the case for input RDDs), get those
    val rddPrefs = rdd.preferredLocations(rdd.partitions(partition)).toList
    if (!rddPrefs.isEmpty) {
      return rddPrefs.map(TaskLocation(_))
    }
    // If the RDD has narrow dependencies, pick the first partition of the first narrow dep
    // that has any placement preferences. Ideally we would choose based on transfer sizes,
    // but this will do for now.
    rdd.dependencies.foreach {
      case n: NarrowDependency[_] =>
        for (inPart <- n.getParents(partition)) {
          val locs = getPreferredLocsInternal(n.rdd, inPart, visited)
          if (locs != Nil) {
            return locs
          }
        }
      case _ =>
    }
    Nil
  }

  def stop() {
    logInfo("Stopping DAGScheduler")
    dagSchedulerActorSupervisor ! PoisonPill
    taskScheduler.stop()
  }
}

private[scheduler] class DAGSchedulerActorSupervisor(dagScheduler: DAGScheduler)
  extends Actor with Logging {

  override val supervisorStrategy =
    OneForOneStrategy() {
      case x: Exception =>
        logError("eventProcesserActor failed; shutting down SparkContext", x)
        try {
          dagScheduler.doCancelAllJobs()
        } catch {
          case t: Throwable => logError("DAGScheduler failed to cancel all jobs.", t)
        }
        dagScheduler.sc.stop()
        Stop
    }

  def receive = {
    case p: Props => sender ! context.actorOf(p)
    case _ => logWarning("received unknown message in DAGSchedulerActorSupervisor")
  }
}

private[scheduler] class DAGSchedulerEventProcessActor(dagScheduler: DAGScheduler)
  extends Actor with Logging {

  override def preStart() {
    // set DAGScheduler for taskScheduler to ensure eventProcessActor is always
    // valid when the messages arrive
    // 设置taskScheduler对DAGScheduler的引用句柄。在此处设置保证了Job提交时候
    // eventProcessActor已经准备就绪
    dagScheduler.taskScheduler.setDAGScheduler(dagScheduler)
  }

  /**
   * The main event loop of the DAG scheduler.
   */
  def receive = {
    case JobSubmitted(jobId, rdd, func, partitions, allowLocal, callSite, listener, properties) =>
      // 提交job，来自于RDD->SparkContext->DAGScheduler的消息。之所以在这需要在这里中转一下，是为了模块功能的一致性。
      dagScheduler.handleJobSubmitted(jobId, rdd, func, partitions, allowLocal, callSite,
        listener, properties)

    case StageCancelled(stageId) =>
      // 消息源org.apache.spark.ui.jobs.JobProgressTab，在GUI上显示一个SparkContext的Job的执行状态。
      // 用户可以cancel一个Stage，会通过SparkContext->DAGScheduler 传递到这里。
      dagScheduler.handleStageCancellation(stageId)

    case JobCancelled(jobId) =>
      // 来自于org.apache.spark.scheduler.JobWaiter的消息。取消一个Job
      dagScheduler.handleJobCancellation(jobId)

    case JobGroupCancelled(groupId) =>
      // 取消整个Job Group
      dagScheduler.handleJobGroupCancelled(groupId)

    case AllJobsCancelled =>
      //取消所有Job
      dagScheduler.doCancelAllJobs()

    case ExecutorAdded(execId, host) =>
      // TaskScheduler得到一个Executor被添加的消息。具体来自org.apache.spark.scheduler.TaskSchedulerImpl.resourceOffers
    dagScheduler.handleExecutorAdded(execId, host)

    case ExecutorLost(execId) =>
      //来自TaskScheduler
      dagScheduler.handleExecutorLost(execId, fetchFailed = false)

    case BeginEvent(task, taskInfo) =>
      // 来自TaskScheduler
      dagScheduler.handleBeginEvent(task, taskInfo)

    case GettingResultEvent(taskInfo) =>
      //处理请求获得TaskResult信息的消息
      dagScheduler.handleGetTaskResult(taskInfo)

    case completion @ CompletionEvent(task, reason, _, _, taskInfo, taskMetrics) =>
      //来自TaskScheduler，报告task是完成或者失败
    dagScheduler.handleTaskCompletion(completion)

    case TaskSetFailed(taskSet, reason) =>
      //来自TaskScheduler，要么TaskSet失败次数超过阈值或者由于Job Cancel。
    dagScheduler.handleTaskSetFailed(taskSet, reason)

    case ResubmitFailedStages =>
      //当一个Stage处理失败时，重试。来自org.apache.spark.scheduler.DAGScheduler.handleTaskCompletion
    dagScheduler.resubmitFailedStages()
  }

  override def postStop() {
    // Cancel any active jobs in postStop hook
    dagScheduler.cleanUpAfterSchedulerStop()
  }
}

private[spark] object DAGScheduler {
  // The time, in millis, to wait for fetch failure events to stop coming in after one is detected;
  // this is a simplistic way to avoid resubmitting tasks in the non-fetchable map stage one by one
  // as more failure events come in
  val RESUBMIT_TIMEOUT = 200.milliseconds

  // The time, in millis, to wake up between polls of the completion queue in order to potentially
  // resubmit failed stages
  val POLL_TIMEOUT = 10L
}
