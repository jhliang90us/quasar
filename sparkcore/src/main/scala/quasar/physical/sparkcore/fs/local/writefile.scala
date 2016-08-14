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

package quasar.physical.sparkcore.fs.local

import quasar.Predef._
import quasar.{Data, DataCodec, DataEncodingError}
import quasar.fs._
import quasar.fs.PathError._
import quasar.fs.FileSystemError._
import quasar.fs.WriteFile._
import quasar.fp.free._
import quasar.effect._

import java.io.{File, PrintWriter, FileOutputStream}

import pathy.Path.posixCodec
import scalaz._
import Scalaz._
import scalaz.concurrent.Task

object writefile {

  def interpret[S[_]](implicit
    s0: KeyValueStore[WriteHandle, PrintWriter, ?] :<: S,
    s1: MonotonicSeq :<: S,
    s2: Task :<: S
  ) : WriteFile ~> Free[S, ?] =
    new (WriteFile ~> Free[S, ?]) {
      def apply[A](wr: WriteFile[A]): Free[S, A] = wr  match {
        case Open(f) => open(f)
        case Write(h, ch) => write(h, ch)
        case Close(h) => close(h)
      }
    }

  def open[S[_]](f: AFile) (implicit
    writers: KeyValueStore.Ops[WriteHandle, PrintWriter, S],
    sequence: MonotonicSeq.Ops[S],
    s2: Task :<: S
  ): Free[S, FileSystemError \/ WriteHandle] = {

    def _open: FileSystemErrT[Free[S, ?], PrintWriter] = {
      val pwProgram: Free[S, FileSystemError \/ PrintWriter] = injectFT[Task, S].apply(Task.delay {
        val file = new File(posixCodec.unsafePrintPath(f))
        \/.fromTryCatchNonFatal(new PrintWriter(new FileOutputStream(file, true)))
          .leftMap(e => pathErr(pathNotFound(f)))
      })
      EitherT(pwProgram)      
    }

    (for {
      pw <- _open
      id <- sequence.next.liftM[FileSystemErrT]
      h = WriteHandle(f, id)
      _ <- writers.put(h, pw).liftM[FileSystemErrT]
    } yield h).run
  }

  def write[S[_]](h: WriteHandle, chunks: Vector[Data])(implicit
    writers: KeyValueStore.Ops[WriteHandle, PrintWriter, S],
    s2: Task :<: S
  ): Free[S, Vector[FileSystemError]] = {

    implicit val codec = DataCodec.Precise

    def _write(pw: PrintWriter): Task[Vector[FileSystemError]] = {

      val maybeLines: Vector[(DataEncodingError \/ String, Data)] =
        chunks.map(data => (DataCodec.render(data), data))

      Task.delay(maybeLines.flatMap {
        case (-\/(error), data) => Vector(writeFailed(data, error.message))
        case (\/-(line), data) =>
          \/.fromTryCatchNonFatal(pw.append(s"$line\n")).fold(
            ex => Vector(writeFailed(data, ex.getMessage)),
            u => Vector.empty[FileSystemError]
          )
      })
    }

    val findAndWrite: OptionT[Free[S,?], Vector[FileSystemError]] = for {
      pw <- writers.get(h)
      errors <- injectFT[Task, S].apply(_write(pw)).liftM[OptionT]
    } yield errors

    findAndWrite.fold (
      errs => errs,
      Vector[FileSystemError](unknownWriteHandle(h))
    )
  }

  def close[S[_]](h: WriteHandle)(implicit
    writers: KeyValueStore.Ops[WriteHandle, PrintWriter, S]
  ): Free[S, Unit] = (for {
    pw <- writers.get(h)
    _ <- writers.delete(h).liftM[OptionT]
  } yield {
    pw.close
  }).run.void
}
