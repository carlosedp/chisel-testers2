// SPDX-License-Identifier: Apache-2.0

package chiseltest.formal.backends

import chiseltest.formal.backends.smt._
import chiseltest.formal.{DoNotModelUndef, FailedBoundedCheckException}
import firrtl._
import firrtl.annotations._
import firrtl.stage._
import firrtl.backends.experimental.smt.random._
import firrtl.backends.experimental.smt._
import chiseltest.simulator._
import firrtl.options.Dependency

sealed trait FormalEngineAnnotation extends NoTargetAnnotation

/** Use a SMTLib based model checker with the CVC4 SMT solver.
  * @note CVC4 often performs better than Z3.
  */
case object CVC4EngineAnnotation extends FormalEngineAnnotation

/** Use a SMTLib based model checker with the Z3 SMT solver.
  * @note Z3 is the most widely available and easiest to install SMT solver.
  */
case object Z3EngineAnnotation extends FormalEngineAnnotation

/** Uses the btormc model checker from the boolector code base.
  * @note btormc is generally faster than Z3 or CVC4 but needs to be built from source
  */
private case object BtormcEngineAnnotation extends FormalEngineAnnotation

/** Formal Verification based on the firrtl compiler's SMT backend and the maltese SMT libraries solver bindings. */
private[chiseltest] object Maltese {
  def bmc(circuit: ir.Circuit, annos: AnnotationSeq, kMax: Int, resetLength: Int = 0): Unit = {
    require(kMax > 0)
    require(resetLength >= 0)
    val targetDir = Compiler.requireTargetDir(annos)
    val modelUndef = !annos.contains(DoNotModelUndef)
    val sysAnnos: AnnotationSeq = if (modelUndef) { DefRandomAnnos ++: annos }
    else { annos }
    val sysInfo = toTransitionSystem(circuit, sysAnnos)
    val checkers = makeCheckers(annos)
    assert(checkers.size == 1, "Parallel checking not supported atm!")
    checkers.head.check(sysInfo.sys, kMax = kMax + resetLength) match {
      case ModelCheckFail(witness) =>
        val writeVcd = annos.contains(WriteVcdAnnotation)
        if (writeVcd) {
          val sim = new TransitionSystemSimulator(sysInfo.sys)
          sim.run(witness, vcdFileName = Some((targetDir / s"${circuit.main}.bmc.vcd").toString))
          val trace = witnessToTrace(sysInfo, witness)
          val treadleState = prepTreadle(circuit, annos, modelUndef)
          val treadleDut = TreadleBackendAnnotation.getSimulator.createContext(treadleState)
          Trace.replayOnSim(trace, treadleDut)
        }
        val failSteps = witness.inputs.length - 1 - resetLength
        throw FailedBoundedCheckException(circuit.main, failSteps)
      case ModelCheckSuccess() => // good!
    }
  }

  // compile low firrtl circuit into a version with all DefRandom registers so that treadle can use it to replay the
  // counter example
  private def prepTreadle(circuit: ir.Circuit, annos: AnnotationSeq, modelUndef: Boolean): CircuitState = {
    if (!modelUndef) { CircuitState(circuit, annos) }
    else {
      val res = firrtlStage.execute(
        Array("--start-from", "low", "-E", "low"),
        FirrtlCircuitAnnotation(circuit) +: annos ++: DefRandomTreadleAnnos
      )
      Compiler.annosToState(res)
    }
  }

  private val LoweringAnnos: AnnotationSeq = Seq(
    // we need to flatten the whole circuit
    RunFirrtlTransformAnnotation(Dependency(FlattenPass)),
    RunFirrtlTransformAnnotation(Dependency[firrtl.passes.InlineInstances])
  )

  private val DefRandomAnnos: AnnotationSeq = Seq(
    RunFirrtlTransformAnnotation(Dependency(UndefinedMemoryBehaviorPass)),
    RunFirrtlTransformAnnotation(Dependency(InvalidToRandomPass))
  )

  private val DefRandomTreadleAnnos: AnnotationSeq =
    RunFirrtlTransformAnnotation(Dependency(DefRandToRegisterPass)) +: DefRandomAnnos

  private case class SysInfo(sys: TransitionSystem, stateMap: Map[String, String], memDepths: Map[String, Int])

  private def toTransitionSystem(circuit: ir.Circuit, annos: AnnotationSeq): SysInfo = {
    val logLevel = Seq() // Seq("-ll", "info")
    val res = firrtlStage.execute(
      Array("--start-from", "low", "-E", "smt2") ++ logLevel,
      FirrtlCircuitAnnotation(circuit) +: annos ++: LoweringAnnos
    )
    val stateMap = FlattenPass.getStateMap(circuit.main, res)
    val memDepths = FlattenPass.getMemoryDepths(circuit.main, res)
    val sys = res.collectFirst { case TransitionSystemAnnotation(s) => s }.get

    // print the system, convenient for debugging, might disable once we have done more testing
    if (true) {
      val targetDir = Compiler.requireTargetDir(annos)
      os.write.over(targetDir / s"${circuit.main}.sys", sys.serialize)
    }

    SysInfo(sys, stateMap, memDepths)
  }

  private def firrtlStage = new FirrtlStage

  private def makeCheckers(annos: AnnotationSeq): Seq[IsModelChecker] = {
    val engines = annos.collect { case a: FormalEngineAnnotation => a }
    assert(engines.nonEmpty, "You need to provide at least one formal engine annotation!")
    engines.map {
      case CVC4EngineAnnotation   => new SMTModelChecker(CVC4SMTLib)
      case Z3EngineAnnotation     => new SMTModelChecker(Z3SMTLib)
      case BtormcEngineAnnotation => throw new NotImplementedError("btor2 backends are not yet supported!")
    }
  }

  private def expandMemWrites(depth: Int, values: Seq[(Option[BigInt], BigInt)]): Seq[BigInt] = {
    var mem = Vector.fill(depth)(BigInt(0))
    values.foreach {
      case (None, value) =>
        mem = Vector.fill(depth)(value)
      case (Some(addr), value) =>
        mem = mem.updated(addr.toInt, value)
    }
    mem
  }

  private def witnessToTrace(sysInfo: SysInfo, w: Witness): Trace = {
    val inputNames = sysInfo.sys.inputs.map(_.name).toIndexedSeq
    val stateNames = sysInfo.sys.states.map(_.name).map(sysInfo.stateMap).toIndexedSeq

    Trace(
      inputs = w.inputs.map(_.toSeq.map { case (i, value) => inputNames(i) -> value }),
      regInit = w.regInit.toSeq.map { case (i, value) => stateNames(i) -> value },
      memInit = w.memInit.toSeq.map { case (i, values) =>
        val name = stateNames(i)
        name -> expandMemWrites(sysInfo.memDepths(name), values)
      }
    )
  }

}
