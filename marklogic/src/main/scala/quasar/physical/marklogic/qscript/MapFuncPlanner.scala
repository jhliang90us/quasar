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

package quasar.physical.marklogic.qscript

import quasar.Predef._
import quasar.NameGenerator
import quasar.contrib.matryoshka.ShowT
import quasar.ejson.EJson
import quasar.fp.eitherT._
import quasar.physical.marklogic.{ErrorMessages, MonadError_}
import quasar.physical.marklogic.ejson.EncodeXQuery
import quasar.physical.marklogic.validation._
import quasar.physical.marklogic.xml._
import quasar.physical.marklogic.xquery._
import quasar.physical.marklogic.xquery.syntax._
import quasar.qscript.{MapFunc, MapFuncs}, MapFuncs._

import eu.timepit.refined.refineV
import matryoshka._, Recursive.ops._
import scalaz.{Apply, EitherT}
import scalaz.std.option._
import scalaz.syntax.monad._
import scalaz.syntax.show._
import scalaz.syntax.std.either._

object MapFuncPlanner {
  import expr.{if_, let_}, axes._

  def apply[T[_[_]]: Recursive: ShowT, F[_]: NameGenerator: PrologW: MonadPlanErr]: AlgebraM[F, MapFunc[T, ?], XQuery] = {
    case Constant(ejson) =>
      type M[A] = EitherT[F, ErrorMessages, A]
      ejson.cataM(EncodeXQuery[M, EJson].encodeXQuery).run.flatMap(_.fold(
        msgs => MonadPlanErr[F].raiseError(MarkLogicPlannerError.unrepresentableEJson(ejson.convertTo[Fix], msgs)),
        _.point[F]))

    case Length(arrOrstr) => qscript.length[F] apply arrOrstr

    // time
    case Date(s)                      => qscript.asDate[F] apply s
    case Time(s)                      => xs.time(s).point[F]
    case Timestamp(s)                 => xs.dateTime(s).point[F]
    case Interval(s)                  => xs.dayTimeDuration(s).point[F]
    case TimeOfDay(dt)                => xs.time(dt).point[F]
    case ToTimestamp(millis)          => qscript.timestampToDateTime[F] apply millis
    case Now()                        => fn.currentDateTime.point[F]

    case ExtractCentury(time)         => fn.ceiling(fn.yearFromDateTime(xs.dateTime(time)) div 100.xqy).point[F]
    case ExtractDayOfMonth(time)      => fn.dayFromDateTime(xs.dateTime(time)).point[F]
    case ExtractDecade(time)          => fn.floor(fn.yearFromDateTime(xs.dateTime(time)) div 10.xqy).point[F]
    case ExtractDayOfWeek(time)       => qscript.asDate[F].apply(time) map (d =>
                                           mkSeq_(xdmp.weekdayFromDate(d) mod 7.xqy))
    case ExtractDayOfYear(time)       => qscript.asDate[F].apply(time) map (xdmp.yeardayFromDate)
    case ExtractEpoch(time)           => qscript.secondsSinceEpoch[F] apply (xs.dateTime(time))
    case ExtractHour(time)            => fn.hoursFromDateTime(xs.dateTime(time)).point[F]
    case ExtractIsoDayOfWeek(time)    => qscript.asDate[F].apply(time) map (xdmp.weekdayFromDate)
    case ExtractMicroseconds(time)    => mkSeq_(fn.secondsFromDateTime(xs.dateTime(time)) * 1000000.xqy).point[F]
    case ExtractMillennium(time)      => mkSeq_(mkSeq_(fn.yearFromDateTime(xs.dateTime(time)) mod 1000.xqy) + 1.xqy).point[F]
    case ExtractMilliseconds(time)    => mkSeq_(fn.secondsFromDateTime(xs.dateTime(time)) * 1000.xqy).point[F]
    case ExtractMinute(time)          => fn.minutesFromDateTime(xs.dateTime(time)).point[F]
    case ExtractMonth(time)           => fn.monthFromDateTime(xs.dateTime(time)).point[F]
    case ExtractQuarter(time)         => qscript.asDate[F].apply(time) map (xdmp.quarterFromDate)
    case ExtractSecond(time)          => fn.secondsFromDateTime(xs.dateTime(time)).point[F]
    case ExtractTimezone(time)        => qscript.timezoneOffsetSeconds[F] apply (xs.dateTime(time))
    case ExtractTimezoneHour(time)    => fn.hoursFromDuration(fn.timezoneFromDateTime(xs.dateTime(time))).point[F]
    case ExtractTimezoneMinute(time)  => fn.minutesFromDuration(fn.timezoneFromDateTime(xs.dateTime(time))).point[F]
    case ExtractWeek(time)            => qscript.asDate[F].apply(time) map (xdmp.weekFromDate)
    case ExtractYear(time)            => fn.yearFromDateTime(xs.dateTime(time)).point[F]

    // math
    case Negate(x)      => (-x).point[F]
    case Add(x, y)      => binOp[F](x, y)(_ + _)
    case Multiply(x, y) => binOp[F](x, y)(_ * _)
    case Subtract(x, y) => binOp[F](x, y)(_ - _)
    case Divide(x, y)   => binOp[F](x, y)(_ div _)
    case Modulo(x, y)   => binOp[F](x, y)(_ mod _)
    case Power(b, e)    => math.pow(b, e).point[F]

    // relations
    case Not(x)              => fn.not(x).point[F]
    case MapFuncs.Eq(x, y)   => binOp[F](x, y)(_ eq _)
    case Neq(x, y)           => binOp[F](x, y)(_ ne _)
    case Lt(x, y)            => binOp[F](x, y)(_ lt _)
    case Lte(x, y)           => binOp[F](x, y)(_ le _)
    case Gt(x, y)            => binOp[F](x, y)(_ gt _)
    case Gte(x, y)           => binOp[F](x, y)(_ ge _)
    case And(x, y)           => binOp[F](x, y)(_ and _)
    case Or(x, y)            => binOp[F](x, y)(_ or _)
    case Between(v1, v2, v3) => ternOp[F](v1, v2, v3)((x1, x2, x3) => mkSeq_(x2 le x1) and mkSeq_(x1 le x3))
    case Cond(p, t, f)       => if_(p).then_(t).else_(f).point[F]

    // string
    case Lower(s)               => fn.lowerCase(s).point[F]
    case Upper(s)               => fn.upperCase(s).point[F]
    case Bool(s)                => xs.boolean(s).point[F]
    case Integer(s)             => xs.integer(s).point[F]
    case Decimal(s)             => xs.decimal(s).point[F]
    case Null(s)                => (ejson.null_[F] |@| qscript.qError[F](s"Invalid coercion to 'null': $s".xs))(
                                     (n, e) => if_ (s eq "null".xs) then_ n else_ e)
    case ToString(x)            => fn.string(x).point[F]
    case Search(in, ptn, ci)    => fn.matches(in, ptn, Some(if_ (ci) then_ "i".xs else_ "".xs)).point[F]
    case Substring(s, loc, len) => fn.substring(s, loc + 1.xqy, some(len)).point[F]

    // structural
    case MakeArray(x) =>
      ejson.singletonArray[F] apply x

    case MakeMap(k, v) =>
      def withLitKey(s: String): F[XQuery] =
        whenValidQName(s)(qn =>
          ejson.singletonObject[F] apply (xs.QName(qn.xs), v))

      k match {
        // Makes numeric strings valid QNames by prepending an underscore
        case XQuery.StringLit(IntegralNumber(s)) =>
          withLitKey("_" + s)

        case XQuery.StringLit(s) =>
          withLitKey(s)

        case _ => ejson.singletonObject[F] apply (k, v)
      }

    case ConcatArrays(x, y) =>
      ejson.arrayConcat[F] apply (x, y)

    case ConcatMaps(x, y) =>
      ejson.objectConcat[F] apply (x, y)

    case ProjectIndex(arr, idx) =>
      ejson.arrayElementAt[F] apply (arr, idx + 1.xqy)

    case ProjectField(src, field) =>
      def projectLit(s: String): F[XQuery] =
        whenValidQName(s)(qn => freshVar[F] map { m =>
          let_(m -> src) return_ (m.xqy `/` child(qn))
        })

      field match {
        case XQuery.Step(_) =>
          (src `/` field).point[F]

        // Makes numeric strings valid QNames by prepending an underscore
        case XQuery.StringLit(IntegralNumber(s)) =>
          projectLit("_" + s)

        case XQuery.StringLit(s) =>
          projectLit(s)

        case _ => qscript.projectField[F] apply (src, xs.QName(field))
      }

    case DeleteField(src, field) =>
      def deleteLit(s: String): F[XQuery] =
        whenValidQName(s)(qn => for {
          m <- freshVar[F]
          n <- mem.nodeDelete[F](m.xqy)
        } yield let_(m -> src) return_ n)

      field match {
        case XQuery.Step(_) =>
          mem.nodeDelete[F](src `/` field)

        case XQuery.StringLit(IntegralNumber(n)) =>
          deleteLit("_" + n)

        case XQuery.StringLit(s) =>
          deleteLit(s)

        case _ => qscript.deleteField[F] apply (src, xs.QName(field))
      }

    // other
    case DupMapKeys(m)       => qscript.elementDupKeys[F]    apply m
    case DupArrayIndices(a)  => ejson.arrayDupIndices[F]     apply a
    case ZipMapKeys(m)       => qscript.zipMapElementKeys[F] apply m
    case ZipArrayIndices(a)  => ejson.arrayZipIndices[F]     apply a
    case Range(x, y)         => (x to y).point[F]

    // FIXME: This isn't correct, just an interim impl to allow some queries to execute.
    case Guard(_, _, cont, _) =>
      s"(: GUARD CONT :)$cont".xqy.point[F]

    case mapFunc => s"(: ${mapFunc.shows} :)()".xqy.point[F]
  }

  ////

  // A string consisting only of digits.
  private val IntegralNumber = "^(\\d+)$".r

  private def binOp[F[_]: NameGenerator: Apply](x: XQuery, y: XQuery)(op: (XQuery, XQuery) => XQuery): F[XQuery] =
    (freshVar[F] |@| freshVar[F])((vx, vy) =>
      mkSeq_(let_(vx -> x, vy -> y) return_ op(vx.xqy, vy.xqy)))

  private def ternOp[F[_]: NameGenerator: Apply](x: XQuery, y: XQuery, z: XQuery)(op: (XQuery, XQuery, XQuery) => XQuery): F[XQuery] =
    (freshVar[F] |@| freshVar[F] |@| freshVar[F])((vx, vy, vz) =>
      mkSeq_(let_(vx -> x, vy -> y, vz -> z) return_ op(vx.xqy, vy.xqy, vz.xqy)))

  private def whenValidQName[F[_]: MonadPlanErr, A](s: String)(f: QName => F[A]): F[A] =
    refineV[IsNCName](s).disjunction
      .map(ncname => f(QName.local(NCName(ncname))))
      .getOrElse(invalidQName(s))

  private def invalidQName[F[_]: MonadPlanErr, A](s: String): F[A] =
    MonadError_[F, MarkLogicPlannerError].raiseError(
      MarkLogicPlannerError.invalidQName(s))
}
