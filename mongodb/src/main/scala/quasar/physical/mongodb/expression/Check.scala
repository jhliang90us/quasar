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

package quasar.physical.mongodb.expression

import quasar.Predef._
import quasar.Type
import quasar.fp._
import quasar.physical.mongodb.Bson

import matryoshka._
import org.threeten.bp.{Instant}
import scalaz._, Scalaz._

/** Runtime type checks, exploiting MongoDB's total ordering for Bson values,
  * seen in the order of declarations in this class.
  */
final case class Check[T[_[_]], EX[_]: Functor](implicit
    T: Corecursive[T],
    I: ExprOpCoreF :<: EX) {
  import Check._

  private val exprCoreFp = ExprOpCoreF.fixpoint[T, EX]
  import exprCoreFp._

  def isNull(expr: T[EX]): T[EX] = $eq($literal(Bson.Null), expr)

  def isNumber(expr: T[EX])     = betweenExcl(Bson.Null,              expr, Bson.Text(""))
  def isString(expr: T[EX])     = between    (Bson.Text(""),          expr, Bson.Doc())
  def isObject(expr: T[EX])     = between    (Bson.Doc(),             expr, Bson.Arr())
  def isArray(expr: T[EX])      = between    (Bson.Arr(),             expr, Bson.Binary(minBinary))
  def isBinary(expr: T[EX])     = between    (Bson.Binary(minBinary), expr, Bson.ObjectId(minOid))
  def isId(expr: T[EX])         = between    (Bson.ObjectId(minOid),  expr, Bson.Bool(false))
  def isBoolean(expr: T[EX])    = betweenIncl(Bson.Bool(false),       expr, Bson.Bool(true))
  /** As of MongoDB 3.0, dates sort before timestamps. The type constraint here
    * ensures that this check is used only when it's safe, although we don't
    * actually use any new op here.
    */
  def isDate(expr: T[EX])(implicit ev: ExprOp3_0F :<: EX) =
    between(Bson.Date(minInstant), expr, minTimestamp)
  /** As of MongoDB 3.0, dates sort before timestamps. The type constraint here
    * ensures that this check is used only when it's safe, although we don't
    * actually use any new op here.
    */
  def isTimestamp(expr: T[EX])(implicit ev: ExprOp3_0F :<: EX) =
    between(minTimestamp, expr, minRegex)

  def isDateOrTimestamp(expr: T[EX]) = between(Bson.Date(minInstant), expr, minRegex)

  // Some types that happen to be adjacent:
  def isNumberOrString(expr: T[EX]) = betweenExcl(Bson.Null, expr, Bson.Doc())
  def isDateTimestampOrBoolean(expr: T[EX]) = between(Bson.Bool(false), expr, minRegex)
  def isSyntaxed(expr: T[EX]) =
    $or(
      $lt(expr, $literal(Bson.Doc())),
      between(Bson.ObjectId(Check.minOid), expr, minRegex))


  private def between(lower: Bson, expr: T[EX], upper: Bson): T[EX] =
    $and(
      $lte($literal(lower), expr),
      $lt(expr, $literal(upper)))
  private def betweenExcl(lower: Bson, expr: T[EX], upper: Bson): T[EX] =
    $and(
      $lt($literal(lower), expr),
      $lt(expr, $literal(upper)))
  private def betweenIncl(lower: Bson, expr: T[EX], upper: Bson): T[EX] =
    $and(
      $lte($literal(lower), expr),
      $lte(expr, $literal(upper)))
}
object Check {
  /** Recognize typecheck constructions after the fact so that they can be
    * translated to other forms. This has to kept strictly in sync with the
    * constructors above.
    */
  // TODO: remove this when we no longer perform after-the-fact translation
  def unapply[T[_[_]]: Recursive, EX[_]: Functor](expr: T[EX])(implicit ev1: ExprOpCoreF :<: EX, ev2: Equal[T[EX]])
      : Option[(T[EX], Type)] = {
    val nilMap = ListMap.empty[String, Bson]
    expr match {
      case IsBetweenExcl(Bson.Null,            x, Bson.Text(""))            => (x, Type.Numeric).some
      case IsBetween(Bson.Text(""),            x, Bson.Doc(`nilMap`))       => (x, Type.Str).some
      case IsBetween(Bson.Doc(`nilMap`),       x, Bson.Arr(Nil))            => (x, Type.AnyObject).some
      case IsBetween(Bson.Arr(Nil),            x, Bson.Binary(`minBinary`)) => (x, Type.AnyArray).some
      case IsBetween(Bson.Binary(`minBinary`), x, Bson.ObjectId(`minOid`))  => (x, Type.Binary).some
      case IsBetween(Bson.ObjectId(`minOid`),  x, Bson.Bool(false))         => (x, Type.Id).some
      case IsBetweenIncl(Bson.Bool(false),     x, Bson.Bool(true))          => (x, Type.Bool).some
      case IsBetween(Bson.Date(`minInstant`),  x, `minRegex`)               => (x, Type.Date ⨿ Type.Timestamp).some
      case IsBetween(Bson.Date(`minInstant`),  x, `minTimestamp`)           => (x, Type.Date).some
      case IsBetween(`minTimestamp`,           x, `minRegex`)               => (x, Type.Timestamp).some

      case IsBetween(Bson.Doc(`nilMap`),       x, Bson.Binary(`minBinary`)) => (x, Type.AnyObject ⨿ Type.AnyArray).some
      case IsBetween(Bson.Binary(`minBinary`), x, `minRegex`)               => (x, Type.Binary ⨿ Type.Id ⨿ Type.Bool ⨿ Type.Date ⨿ Type.Timestamp).some
      case IsBetween(Bson.Bool(false),         x, `minRegex`)               => (x, Type.Bool ⨿ Type.Date ⨿ Type.Timestamp).some
      case IsBetweenExcl(Bson.Null,            x, Bson.Doc(`nilMap`))       => (x, Type.Numeric ⨿ Type.Str).some

      case $or(
            $lt(x1, $literal(Bson.Doc(`nilMap`))),
            IsBetween(Bson.ObjectId(`minOid`), x2, `minRegex`))
            if x1 ≟ x2 =>
        (x1, Type.Null ⨿ Type.Numeric ⨿ Type.Str ⨿ Type.Id ⨿ Type.Bool ⨿ Type.Date ⨿ Type.Timestamp).some

      case _ => None
    }
  }

  object IsBetween {
    def unapply[T[_[_]]: Recursive, EX[_]: Functor](expr: T[EX])(implicit ev1: ExprOpCoreF :<: EX, ev2: Equal[T[EX]])
      : Option[(Bson, T[EX], Bson)] =
      expr match {
        case $and(
              $lte($literal(const1), x1),
              $lt(x2, $literal(const2)))
              if x1 ≟ x2 =>
          (const1, x1, const2).some
        case _ => None
      }
  }

  object IsBetweenExcl {
    def unapply[T[_[_]]: Recursive, EX[_]: Functor](expr: T[EX])(implicit ev1: ExprOpCoreF :<: EX, ev2: Equal[T[EX]])
      : Option[(Bson, T[EX], Bson)] =
      expr match {
        case $and(
              $lt($literal(const1), x1),
              $lt(x2, $literal(const2)))
              if x1 ≟ x2 =>
          (const1, x1, const2).some
        case _ => None
      }
  }

  object IsBetweenIncl {
    def unapply[T[_[_]]: Recursive, EX[_]: Functor](expr: T[EX])(implicit ev1: ExprOpCoreF :<: EX, ev2: Equal[T[EX]])
      : Option[(Bson, T[EX], Bson)] =
      expr match {
        case $and(
              $lte($literal(const1), x1),
              $lte(x2, $literal(const2)))
              if x1 ≟ x2 =>
          (const1, x1, const2).some
        case _ => None
      }
  }

  val minBinary = ImmutableArray.fromArray(scala.Array[Byte]())
  val minInstant = Instant.ofEpochMilli(0)  // FIXME: should be some negative value (this will miss any date before 1970)
  val minTimestamp = Bson.Timestamp.fromInstant(minInstant, 0)
  val minOid =
    ImmutableArray.fromArray(scala.Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
  val minRegex = Bson.Regex("", "")
}
