/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.qscript

import quasar.Predef._
import quasar.{Data, LogicalPlan => LP}
import quasar.contrib.matryoshka._
import quasar.fp._
import quasar.qscript.MapFuncs._
import quasar.std.StdLib._

import matryoshka._, FunctorT.ops._
import pathy.Path._
import scalaz._, Scalaz._

class ShiftReadSpec extends quasar.Qspec with QScriptHelpers {
  val rewrite = new Rewrite[Fix]

  "shiftRead" should {
    "eliminate Read nodes from a simple query" in {
      val sampleFile = rootDir </> file("bar")

      val qScript =
        chain(
          ReadR(sampleFile),
          QC.inj(LeftShift((), HoleF, Free.point(RightSide))))

      val newQScript = transFutu(qScript)(ShiftRead[Fix, QS, QST].shiftRead(idPrism.reverseGet)(_))

      // TODO: Rewrite away the `IncludeId`.
      newQScript.transCata(rewrite.normalize) must_=
      Fix(QCT.inj(Map(
        Fix(SRT.inj(Const[ShiftedRead, Fix[QST]](ShiftedRead(sampleFile, IncludeId)))),
        Free.roll(ProjectIndex(HoleF, IntLit(1))))))
    }
  }

  "shift a simple aggregated read" in {
    convert(listContents.some,
      structural.MakeObject(
        LP.Constant(Data.Str("0")),
        agg.Count(lpRead("/foo/bar")).embed).embed).map(
      transFutu(_)(ShiftRead[Fix, QS, QST].shiftRead(idPrism.reverseGet)(_))
        .transCata(rewrite.normalize[QST])) must
    equal(chain(
      SRT.inj(Const[ShiftedRead, Fix[QST]](
        ShiftedRead(rootDir </> dir("foo") </> file("bar"), IncludeId))),
      QCT.inj(Reduce((),
        NullLit(),
        List(ReduceFuncs.Count(Free.roll(ProjectIndex(HoleF, IntLit[Fix, Hole](1))))),
        Free.roll(MakeMap(StrLit("0"), Free.point(ReduceIndex(0))))))).some)
  }
}
