// SPDX-License-Identifier: Apache-2.0

package chiseltest.simulator

import chiseltest.simulator.ipc.{IPCSimulatorContext, VpiVerilogHarnessGenerator}
import firrtl.annotations.NoTargetAnnotation
import firrtl.{AnnotationSeq, CircuitState}

import java.io.IOException

case object VcsBackendAnnotation extends SimulatorAnnotation {
  override def getSimulator: Simulator = VcsSimulator
}

/** VCS specific options */
trait VcsOption extends NoTargetAnnotation

/** adds flags to the invocation of VCS */
case class VcsFlags(flags: Seq[String]) extends VcsOption

/** adds flags to the C++ compiler in the Makefile generated by Vcs */
case class VcsCFlags(flags: Seq[String]) extends VcsOption

/** adds flags to the simulation binary created by VCS */
case class VcsSimFlags(flags: Seq[String]) extends VcsOption

private object VcsSimulator extends Simulator {
  override def name: String = "vcs"

  /** is this simulator installed on the local machine? */
  override def isAvailable: Boolean = {
    val binaryFound = os.proc("which", "vcs").call().exitCode == 0
    binaryFound && majorVersion >= 2019
  }

  private lazy val version: (Int, Int) = {
    try {
      val VcsVersionRegex = """vcs script version : P-([0-9]+)\.(\d+)""".r
      val text = os.proc("vcs", "-ID").call(check = false, stderr = os.Pipe).out.trim

      VcsVersionRegex.findFirstMatchIn(text).map { m =>
        (m.group(1).toInt, m.group(2).toInt)
      } match {
        case Some(tuple) => tuple
        case None =>
          throw new SimulatorNotFoundException(s"""Could not parse vcs version in string "$text" """)
      }
    } catch {
      case _: IOException =>
        throw new SimulatorNotFoundException(
          s"""Unable to determine VCS version by running command "vcs -ID" is it installed?"""
        )
    }
  }

  /** search the local computer for an installation of this simulator and print versions */
  def findVersions(): Unit = {
    if (isAvailable) {
      val (maj, min) = version
      println(s"Found Vcs $maj.$min")
    }
  }

  private def majorVersion: Int = version._1
  private def minorVersion: Int = version._2

  override def waveformFormats = Seq(WriteVcdAnnotation, WriteVpdAnnotation)

  /** start a new simulation
    *
    * @param state LoFirrtl circuit + annotations
    */
  override def createContext(state: CircuitState): SimulatorContext = {
    // we will create the simulation in the target directory
    val targetDir = Compiler.requireTargetDir(state.annotations)
    val toplevel = TopmoduleInfo(state.circuit)

    // Create the VPI files that vcs needs + a custom harness
    val waveformExt = Simulator.getWavformFormat(state.annotations)
    val moduleNames = GetModuleNames(state.circuit)
    val verilogHarness = generateHarness(targetDir, toplevel, moduleNames)

    // compile low firrtl to System Verilog for verilator to use
    Compiler.lowFirrtlToSystemVerilog(state, Seq())

    // turn SystemVerilog into simulation binary
    val userSimFlags = state.annotations.collectFirst { case VcsSimFlags(f) => f }.getOrElse(Seq.empty)
    val simCmd = compileSimulation(toplevel.name, targetDir, verilogHarness, state.annotations) ++
      userSimFlags ++
      waveformFlags(toplevel.name, state.annotations)

    // the binary we created communicates using our standard IPC interface
    new IPCSimulatorContext(simCmd, toplevel, VcsSimulator)
  }

  private def waveformFlags(topName: String, annos: AnnotationSeq): Seq[String] = {
    val ext = Simulator.getWavformFormat(annos)
    if (ext.isEmpty) { Seq("-none") }
    else if (ext == "vpd") {
      Seq(s"+vcdplusfile=$topName.$ext")
    } else {
      Seq(s"+dumpfile=$topName.$ext")
    }
  }

  /** executes VCS in order to generate a simulation binary */
  private def compileSimulation(
    topName:        String,
    targetDir:      os.Path,
    verilogHarness: String,
    annos:          AnnotationSeq
  ): Seq[String] = {
    val flags = generateFlags(topName, targetDir, annos)
    val cmd = List("vcs") ++ flags ++ List("-o", topName, s"$topName.sv", verilogHarness, "vpi.cpp")
    val ret = os.proc(cmd).call(cwd = targetDir)

    assert(ret.exitCode == 0, s"vcs command failed on circuit ${topName} in work dir $targetDir")
    val simBinary = targetDir / topName
    assert(os.exists(simBinary), s"Failed to generate simulation binary: $simBinary")
    Seq("./" + topName)
  }

  private def generateFlags(topName: String, targetDir: os.Path, annos: AnnotationSeq): Seq[String] = {
    // generate C flags
    val userCFlags = annos.collectFirst { case VcsCFlags(f) => f }.getOrElse(Seq.empty)
    val cFlags = DefaultCFlags(targetDir) ++ userCFlags

    // combine all flags
    val userFlags = annos.collectFirst { case VcsFlags(f) => f }.getOrElse(Seq.empty)
    val flags = DefaultFlags(topName, cFlags) ++ userFlags
    flags
  }

  private def DefaultCFlags(targetDir: os.Path) = List(
    "-I$VCS_HOME/include",
    s"-I$targetDir", // TODO: is this actually necessary?
    "-fPIC",
    "-std=c++11"
  )

  private def DefaultFlags(topName: String, cFlags: Seq[String]) = List(
    "-full64",
    "-quiet",
    "-sverilog",
    "-timescale=1ns/1ps",
    "-debug_pp",
    s"-Mdir=$topName.csrc",
    "+v2k",
    "+vpi",
    "+vcs+lic+wait",
    "+vcs+initreg+random",
    "+define+CLOCK_PERIOD=1",
    "-P",
    "vpi.tab",
    "-cpp",
    "g++",
    "-O2",
    "-LDFLAGS",
    "-lstdc++",
    "-CFLAGS",
    "\"" + cFlags.mkString(" ") + "\""
  )

  private def generateHarness(targetDir: os.Path, toplevel: TopmoduleInfo, moduleNames: Seq[String]): String = {
    val topName = toplevel.name

    // copy the VPI files + generate a custom verilog harness
    CopyVpiFiles(targetDir)
    val verilogHarnessFileName = s"${topName}-harness.sv"
    val emittedStuff = VpiVerilogHarnessGenerator.codeGen(toplevel, moduleNames, useVpdDump = true)

    os.write.over(targetDir / verilogHarnessFileName, emittedStuff)
    verilogHarnessFileName
  }
}

/** Copies the files needed for the VPI based simulator interface.
  */
private object CopyVpiFiles {
  def apply(destinationDirPath: os.Path): Unit = {
    // note: vpi_register.cpp is only used by icarus, not by VCS
    val files = Seq("sim_api.h", "vpi.h", "vpi.cpp", "vpi.tab", "vpi_register.cpp")
    val resourcePrefix = "/simulator/"

    files.foreach { name =>
      val dst = destinationDirPath / name
      val src = getClass.getResourceAsStream(resourcePrefix + name)
      os.write.over(target = dst, data = src, createFolders = true)
    }
  }
}
