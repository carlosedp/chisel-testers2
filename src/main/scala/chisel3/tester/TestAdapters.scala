/*
Copyright 2020 The Regents of the University of California (Regents)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package chisel3.tester

import chisel3._
import chisel3.util._
import chiseltest._

package object TestAdapters {
  @deprecated("Use implicit conversion to DecoupledDriver with setSourceClock or implicit clock resolution", "0.1")
  class ReadyValidSource[T <: Data](x: ReadyValidIO[T], clk: Clock) {
    // TODO assumption this never goes out of scope
    x.valid.poke(false.B)

    def enqueueNow(data: T): Unit = timescope {
      x.ready.expect(true.B)
      x.bits.poke(data)
      x.valid.poke(true.B)
      clk.step(1)
    }

    def enqueue(data: T): Unit = timescope {
      x.valid.poke(true.B)
      while (x.ready.peek().litToBoolean == false) {
        clk.step(1)
      }
      x.bits.poke(data)
      clk.step(1)
    }

    def enqueueSeq(data: Seq[T]): Unit = timescope {
      for (elt <- data) {
        enqueue(elt)
      }
    }
  }

  @deprecated("Use implicit conversion to DecoupledDriver with setSinkClock or implicit clock resolution", "0.1")
  class ReadyValidSink[T <: Data](x: ReadyValidIO[T], clk: Clock) {
    // TODO assumption this never goes out of scope
    x.ready.poke(false.B)

    def waitForValid(): Unit = {
      while (x.valid.peek().litToBoolean == false) {
        clk.step(1)
      }
    }

    def expectDequeue(data: T): Unit = timescope {
      x.ready.poke(true.B)
      waitForValid()
      expectDequeueNow(data)
    }

    def expectDequeueNow(data: T): Unit = timescope {
      x.valid.expect(true.B)
      x.bits.expect(data)
      x.ready.poke(true.B)
      clk.step(1)
    }

    def expectPeek(data: T): Unit = {
      x.valid.expect(true.B)
      x.bits.expect(data)
    }

    def expectInvalid(): Unit = {
      x.valid.expect(false.B)
    }
  }
}
