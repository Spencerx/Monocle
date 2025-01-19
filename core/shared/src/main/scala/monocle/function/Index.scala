package monocle.function

import monocle.{Iso, Optional}

import scala.annotation.{implicitNotFound, tailrec}
import scala.collection.SeqOps
import scala.collection.immutable.{ListMap, SortedMap}
import scala.util.Try

/** Typeclass that defines an [[Optional]] from an `S` to an `A` at an index `I` [[Index]] is less powerful than [[At]]
  * as it cannot create or delete value
  * @tparam S
  *   source of [[Optional]]
  * @tparam I
  *   index
  * @tparam A
  *   target of [[Optional]], `A` is supposed to be unique for a given pair `(S, I)`
  */
@implicitNotFound(
  "Could not find an instance of Index[${S},${I},${A}], please check Monocle instance location policy to " + "find out which import is necessary"
)
abstract class Index[S, -I, A] extends Serializable {
  def index(i: I): Optional[S, A]
}

trait IndexFunctions {
  def index[S, I, A](i: I)(implicit ev: Index[S, I, A]): Optional[S, A] =
    ev.index(i)
}

object Index extends IndexFunctions {
  def apply[S, I, A](optional: I => Optional[S, A]): Index[S, I, A] =
    (i: I) => optional(i)

  /** lift an instance of [[Index]] using an [[Iso]] */
  def fromIso[S, A, I, B](iso: Iso[S, A])(implicit ev: Index[A, I, B]): Index[S, I, B] =
    Index(key => iso.andThen(ev.index(key)))

  def fromAt[S, I, A](implicit ev: At[S, I, Option[A]]): Index[S, I, A] =
    Index(
      ev.at(_).andThen(monocle.std.option.some[A])
    )

  /** *********************************************************************************************
    */
  /** Std instances */
  /** *********************************************************************************************
    */
  implicit def seqIndex[A, S[B] <: SeqOps[B, S, S[B]]]: Index[S[A], Int, A] =
    Index(i =>
      if (i < 0) Optional.void
      else
        Optional[S[A], A](s => if (s.isDefinedAt(i)) Some(s(i)) else None)(a =>
          s => if (s.isDefinedAt(i)) s.updated(i, a) else s
        )
    )

  implicit def listIndex[A]: Index[List[A], Int, A] =
    Index(i =>
      if (i < 0) Optional.void
      else
        Optional[List[A], A](_.drop(i).headOption)(a => s => Try(s.updated(i, a)).getOrElse(s))
    )

  implicit def lazyListIndex[A]: Index[LazyList[A], Int, A] =
    Index(i =>
      if (i < 0) Optional.void
      else
        Optional[LazyList[A], A](_.drop(i).headOption)(a =>
          s =>
            s.zipWithIndex.map { case (value, index) =>
              if (i == index) a else value
            }
        )
    )

  implicit def listMapIndex[K, V]: Index[ListMap[K, V], K, V] = fromAt

  implicit def mapIndex[K, V]: Index[Map[K, V], K, V] = fromAt

  implicit def sortedMapIndex[K, V]: Index[SortedMap[K, V], K, V] = fromAt

  implicit val stringIndex: Index[String, Int, Char] =
    Index(key => monocle.std.string.stringToList.andThen(Index.index[List[Char], Int, Char](key)))

  implicit def vectorIndex[A]: Index[Vector[A], Int, A] = seqIndex

  /** *********************************************************************************************
    */
  /** Cats instances */
  /** *********************************************************************************************
    */
  import cats.data.{Chain, NonEmptyChain, NonEmptyList, NonEmptyVector, OneAnd}
  import monocle.function.Cons1.{necCons1, nelCons1, nevCons1, oneAndCons1}

  implicit def chainIndex[A]: Index[Chain[A], Int, A] =
    new Index[Chain[A], Int, A] {
      def index(i: Int) =
        Optional[Chain[A], A] { c =>
          if (i < 0)
            None
          else {
            val it = c.iterator.drop(i)
            if (it.hasNext) Some(it.next())
            else None
          }
        } { a => c =>
          @tailrec
          def go(cur: Int, oldC: Chain[A], newC: Chain[A]): Chain[A] =
            oldC.uncons match {
              case Some((h, t)) =>
                if (cur == i)
                  newC.append(a).concat(t)
                else
                  go(cur + 1, t, newC.append(h))
              case None => newC
            }

          if (i >= 0 && i < c.length) go(0, c, Chain.empty) else c
        }
    }

  implicit def necIndex[A]: Index[NonEmptyChain[A], Int, A] =
    new Index[NonEmptyChain[A], Int, A] {
      def index(i: Int): Optional[NonEmptyChain[A], A] =
        i match {
          case 0 => necCons1.head
          case _ => necCons1.tail.index(i - 1)
        }
    }

  implicit def nelIndex[A]: Index[NonEmptyList[A], Int, A] =
    new Index[NonEmptyList[A], Int, A] {
      def index(i: Int): Optional[NonEmptyList[A], A] =
        i match {
          case 0 => nelCons1.head
          case _ => nelCons1.tail.index(i - 1)
        }
    }

  implicit def nevIndex[A]: Index[NonEmptyVector[A], Int, A] =
    new Index[NonEmptyVector[A], Int, A] {
      def index(i: Int): Optional[NonEmptyVector[A], A] =
        i match {
          case 0 => nevCons1.head
          case _ => nevCons1.tail.index(i - 1)
        }
    }

  implicit def oneAndIndex[T[_], A](implicit ev: Index[T[A], Int, A]): Index[OneAnd[T, A], Int, A] =
    Index {
      case 0 => oneAndCons1[T, A].head
      case i => oneAndCons1[T, A].tail.index(i - 1)
    }
}
