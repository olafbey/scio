// /*
//  * Copyright 2016 Spotify AB.
//  *
//  * Licensed under the Apache License, Version 2.0 (the "License");
//  * you may not use this file except in compliance with the License.
//  * You may obtain a copy of the License at
//  *
//  *     http://www.apache.org/licenses/LICENSE-2.0
//  *
//  * Unless required by applicable law or agreed to in writing,
//  * software distributed under the License is distributed on an
//  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//  * KIND, either express or implied.  See the License for the
//  * specific language governing permissions and limitations
//  * under the License.
//  */

package com.spotify.scio.coders

import java.io.{InputStream, OutputStream}
import org.apache.beam.sdk.coders.{ Coder => BCoder, _}
// import org.apache.beam.sdk.util.CoderUtils
import com.twitter.bijection._
// import java.io.{ ObjectInputStream, ObjectOutputStream }
import scala.reflect.ClassTag
import scala.collection.{ mutable => m }

final class AvroRawCoder[T](@transient var schema: org.apache.avro.Schema) extends AtomicCoder[T] {

  // makes the schema scerializable
  val schemaString = schema.toString

  @transient lazy val _schema = new org.apache.avro.Schema.Parser().parse(schemaString)

  @transient lazy val model = new org.apache.avro.specific.SpecificData()
  @transient lazy val encoder = new org.apache.avro.message.RawMessageEncoder[T](model, _schema)
  @transient lazy val decoder = new org.apache.avro.message.RawMessageDecoder[T](model, _schema)

  def encode(value: T, os: OutputStream): Unit =
    encoder.encode(value, os)

  def decode(is: InputStream): T =
    decoder.decode(is)
}

//
// Derive Coder from Serializable values
//
// private[scio] class SerializableCoder[T] extends Coder[T] {
//   def decode(in: InputStream): T =
//     new ObjectInputStream(in).readObject().asInstanceOf[T]
//   def encode(ts: T, out: OutputStream): Unit =
//     new ObjectOutputStream(out).writeObject(ts)
// }

// sealed trait FromSerializable {
//   // XXX: probably a bad idea...
//   // implicit def serializableCoder[T <: Serializable](implicit l: shapeless.LowPriority): Coder[T] =
//   //   new SerializableCoder[T]

//   private[scio] implicit def function1Coder[I, O]: Coder[I => O] =
//     new SerializableCoder[I => O]
// }

//
// Derive Coder from twitter Bijection
//
final class CollectionfromBijection[A, B](
  b: Bijection[Seq[A], B], c: BCoder[Seq[A]]) extends AtomicCoder[B] {
    def encode(ts: B, out: OutputStream): Unit =
      c.encode(b.invert(ts), out)
    def decode(in: InputStream): B =
      b(c.decode(in))
}

final class MapfromBijection[K, A, B](
  b: Bijection[Map[K, A], B], c: BCoder[Map[K, A]]) extends AtomicCoder[B] {
    def encode(ts: B, out: OutputStream): Unit =
      c.encode(b.invert(ts), out)
    def decode(in: InputStream): B =
      b(c.decode(in))
}

sealed trait FromBijection {
  implicit def collectionfromBijection[A, B](
    implicit b: Bijection[Seq[A], B], //TODO: should I use ImplicitBijection ?
             c: Coder[Seq[A]]): Coder[B] =
    Coder.transform(c) { ca =>
      Coder.beam(new CollectionfromBijection[A, B](b, ca))
    }

  implicit def mapfromBijection[K, A, B](
    implicit b: Bijection[Map[K, A], B], //TODO: should I use ImplicitBijection ?
             c: Coder[Map[K, A]]): Coder[B] =
    Coder.transform(c) { cm =>
      Coder.beam(new MapfromBijection[K, A, B](b, cm))
    }
}

//
// Derive Coder using Magnolia
//
private final object Help {
  @inline def onErrorMsg[T](msg: String)(f: => T) =
    try { f }
    catch { case e: Exception =>
      throw new RuntimeException(msg, e)
    }
}

/**
* Create a serializable coder by trashing all references to magnolia classes
*/

private final object Derived extends Serializable {
  import magnolia._
  import Coder.xmap

  private def addErrInfo[A](label: String, c: Coder[A]): Coder[A] =
    Coder.transform(c) { bc =>
      Coder.beam(new AtomicCoder[A] {
        def encode(value: A, os: OutputStream): Unit =
          Help.onErrorMsg(s"Exception while trying to `encode` field ${label} in ${value}") {
            bc.encode(value, os)
          }
        def decode(is: InputStream): A =
          Help.onErrorMsg(s"Exception while trying to `decode` field ${label}") {
            bc.decode(is)
          }
      })
    }

  private def add[A](a: Coder[Seq[A]], b: Coder[A]): Coder[Seq[A]] =
    Coder.transform(a) { ca =>
      Coder.transform(b) { cb =>
        Coder.beam(new AtomicCoder[Seq[A]]{
          def encode(value: Seq[A], os: OutputStream): Unit = {
            ca.encode(value.init, os)
            cb.encode(value.last, os)
          }
          def decode(is: InputStream): Seq[A] = {
            ca.decode(is) :+ cb.decode(is)
          }
        })
      }
    }

  private def toSeq[A](a: Coder[A]): Coder[Seq[A]] =
    xmap(a)(a => Seq(a), _.head)

  def combineCoder[T](ps: Seq[Param[Coder, T]], rawConstruct: Seq[Any] => T): Coder[T] = {
    val cs =
      ps.map { case p =>
        addErrInfo[Any](p.label, p.typeclass.asInstanceOf[Coder[Any]])
      }
    val coderValues =
      cs.tail.foldLeft(toSeq(cs.head)) { case (cs, c) =>
        add(cs, c)
      }
    xmap(coderValues)(rawConstruct, v => ps.map(_.dereference(v)))
  }
}

trait LowPriorityCoderDerivation {
  import language.experimental.macros, magnolia._
  import com.spotify.scio.avro.types.CoderUtils

  type Typeclass[T] = Coder[T]

  def combine[T](ctx: CaseClass[Coder, T]): Coder[T] =
    Derived.combineCoder(ctx.parameters, ctx.rawConstruct _)

  def dispatch[T](sealedTrait: SealedTrait[Coder, T]): Coder[T] = {
    val idx: Map[magnolia.TypeName, Int] =
      sealedTrait.subtypes.map(_.typeName).zipWithIndex.toMap
    val coders: Map[Int, Coder[T]] =
      sealedTrait.subtypes.map(_.typeclass.asInstanceOf[Coder[T]]).zipWithIndex
        .map{ case (c, i) => (i, c) }
        .toMap

    Coder.disjonction[T, Int](coders){ t => sealedTrait.dispatch(t) { subtype => idx(subtype.typeName) } }
  }

  implicit def gen[T]: Coder[T] = macro CoderUtils.wrappedCoder[T]
}

import org.apache.avro.generic.GenericRecord
import org.apache.avro.Schema

private final class SlowGenericRecordCoder extends AtomicCoder[GenericRecord]{

  var coder: BCoder[GenericRecord] = _
  // TODO: can we find something more efficient than String ?
  val sc = StringUtf8Coder.of()

  def encode(value: GenericRecord, os: OutputStream): Unit = {
    val schema = value.getSchema
    if(coder == null) {
      coder = AvroCoder.of(schema)
    }
    sc.encode(schema.toString, os)
    coder.encode(value, os)
  }

  def decode(is: InputStream): GenericRecord = {
    val schemaStr = sc.decode(is)
    if(coder == null) {
      val schema = new Schema.Parser().parse(schemaStr)
      coder = AvroCoder.of(schema)
    }
    coder.decode(is)
  }
}

sealed trait AvroCoders {
  import language.experimental.macros
  // TODO: Use a coder that does not serialize the schema
  def genericRecordCoder(schema: Schema): Coder[GenericRecord] =
    Coder.beam(new AvroRawCoder(schema))

  // XXX: similar to GenericAvroSerializer
  def slowGenericRecordCoder: Coder[GenericRecord] =
    Coder.beam(new SlowGenericRecordCoder)

  import org.apache.avro.specific.SpecificRecordBase
  implicit def genAvro[T <: SpecificRecordBase]: Coder[T] =
    macro com.spotify.scio.avro.types.CoderUtils.staticInvokeCoder[T]
}

// //
// // Protobuf Coders
// //
// sealed trait ProtobufCoders {
//   implicit def bytestringCoder: Coder[com.google.protobuf.ByteString] = ???
//   implicit def timestampCoder: Coder[com.google.protobuf.Timestamp] = ???
//   implicit def protoGeneratedMessageCoder[T <: com.google.protobuf.GeneratedMessageV3]: Coder[T] = ???
// }

// //
// // Java Coders
// //
sealed trait JavaCoders {
  self: BaseCoders with FromBijection =>

  implicit def uriCoder: Coder[java.net.URI] = ???
  implicit def pathCoder: Coder[java.nio.file.Path] = ???
  import java.lang.{Iterable => jIterable}
  implicit def jIterableCoder[T](implicit c: Coder[T]): Coder[jIterable[T]] = ???
  // Could be derived from Bijection but since it's a very common one let's just support it.
  implicit def jlistCoder[T](implicit c: Coder[T]): Coder[java.util.List[T]] =
    collectionfromBijection[T, java.util.List[T]]

  private def fromScalaCoder[J <: java.lang.Number, S <: AnyVal](coder: Coder[S]): Coder[J] =
    coder.asInstanceOf[Coder[J]]

  implicit val jIntegerCoder: Coder[java.lang.Integer] = fromScalaCoder(Coder.intCoder)
  implicit val jLongCoder: Coder[java.lang.Long] = fromScalaCoder(Coder.longCoder)
  implicit val jDoubleCoder: Coder[java.lang.Double] = fromScalaCoder(Coder.doubleCoder)
  // TODO: Byte, Float, Short

  // implicit def mutationCaseCoder: Coder[com.google.bigtable.v2.Mutation.MutationCase] = ???
  // implicit def mutationCoder: Coder[com.google.bigtable.v2.Mutation] = ???

  // implicit def boundedWindowCoder: Coder[org.apache.beam.sdk.transforms.windowing.BoundedWindow] = ???
  // implicit def intervalWindowCoder: Coder[org.apache.beam.sdk.transforms.windowing.IntervalWindow] = ???
  // implicit def paneinfoCoder: Coder[org.apache.beam.sdk.transforms.windowing.PaneInfo] = ???
  // implicit def instantCoder: Coder[org.joda.time.Instant] = ???
  implicit def tablerowCoder: Coder[com.google.api.services.bigquery.model.TableRow] =
    Coder.beam(org.apache.beam.sdk.io.gcp.bigquery.TableRowJsonCoder.of())
  // implicit def messageCoder: Coder[org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage] = ???
  // implicit def entityCoder: Coder[com.google.datastore.v1.Entity] = ???
  // implicit def statcounterCoder: Coder[com.spotify.scio.util.StatCounter] = ???
}

sealed trait AlgebirdCoders {
  import com.twitter.algebird._
  implicit def cmsCoder[K]: Coder[CMS[K]] = Coder.fallback
  implicit def bfCoder[K]: Coder[BF[K]] = Coder.fallback
  implicit def topKCoder[K]: Coder[TopK[K]] = Coder.fallback
}

private final object UnitCoder extends AtomicCoder[Unit]{
  def encode(value: Unit, os: OutputStream): Unit = ()
  def decode(is: InputStream): Unit = ()
}

private final object NothingCoder extends AtomicCoder[Nothing] {
  def encode(value: Nothing, os: OutputStream): Unit = ()
  def decode(is: InputStream): Nothing = ??? // can't possibly happen
}

private class OptionCoder[T](tc: BCoder[T]) extends AtomicCoder[Option[T]] {
  val bcoder = BooleanCoder.of().asInstanceOf[BCoder[Boolean]]
  def encode(value: Option[T], os: OutputStream): Unit = {
    bcoder.encode(value.isDefined, os)
    value.foreach { tc.encode(_, os) }
  }

  def decode(is: InputStream): Option[T] =
    Option(bcoder.decode(is)).collect {
      case true => tc.decode(is)
    }
}

private class SeqCoder[T](bc: BCoder[T]) extends AtomicCoder[Seq[T]] {
  val lc = VarIntCoder.of()
  def decode(in: InputStream): Seq[T] = {
    val l = lc.decode(in)
    (1 to l).map { _ =>
      bc.decode(in)
    }
  }

  def encode(ts: Seq[T], out: OutputStream): Unit = {
    lc.encode(ts.length, out)
    ts.foreach { v => bc.encode(v, out) }
  }
}

private class ListCoder[T](bc: BCoder[T]) extends AtomicCoder[List[T]] {
  val seqCoder = new SeqCoder[T](bc)
  def encode(value: List[T], os: OutputStream): Unit =
    seqCoder.encode(value.toSeq, os)
  def decode(is: InputStream): List[T] =
    seqCoder.decode(is).toList
}

private class IterableCoder[T](bc: BCoder[T]) extends AtomicCoder[Iterable[T]] {
  val seqCoder = new SeqCoder[T](bc)
  def encode(value: Iterable[T], os: OutputStream): Unit =
    seqCoder.encode(value.toSeq, os)
  def decode(is: InputStream): Iterable[T] =
    seqCoder.decode(is)
}

// private class VectorCoder[T: Coder] extends Coder[Vector[T]] {
//   val seqCoder = new SeqCoder[T]
//   def encode(value: Vector[T], os: OutputStream): Unit =
//     seqCoder.encode(value.toSeq, os)
//   def decode(is: InputStream): Vector[T] =
//     seqCoder.decode(is).toVector
// }

private class ArrayCoder[T : ClassTag](bc: BCoder[T]) extends AtomicCoder[Array[T]] {
  val seqCoder = new SeqCoder[T](bc)
  def encode(value: Array[T], os: OutputStream): Unit =
    seqCoder.encode(value.toSeq, os)
  def decode(is: InputStream): Array[T] =
    seqCoder.decode(is).toArray
}

private class ArrayBufferCoder[T](c: BCoder[T]) extends AtomicCoder[m.ArrayBuffer[T]] {
  val seqCoder = new SeqCoder[T](c)
  def encode(value: m.ArrayBuffer[T], os: OutputStream): Unit =
    seqCoder.encode(value.toSeq, os)
  def decode(is: InputStream): m.ArrayBuffer[T] =
    m.ArrayBuffer(seqCoder.decode(is):_*)
}

private class MapCoder[K, V](kc: BCoder[K], vc: BCoder[V]) extends AtomicCoder[Map[K, V]] {
  val lc = VarIntCoder.of()
  def decode(in: InputStream): Map[K, V] = {
    val l = lc.decode(in)
    (1 to l).map { _ =>
      val k = kc.decode(in)
      val v = vc.decode(in)
      (k, v)
    }.toMap
  }

  def encode(ts: Map[K, V], out: OutputStream): Unit = {
    lc.encode(ts.size, out)
    ts.foreach { case (k, v) =>
      kc.encode(k, out)
      vc.encode(v, out)
    }
  }
}

private class MutableMapCoder[K, V](kc: BCoder[K], vc: BCoder[V]) extends AtomicCoder[m.Map[K, V]] {
  val lc = VarIntCoder.of()
  def decode(in: InputStream): m.Map[K, V] = {
    val l = lc.decode(in)
    m.Map((1 to l).map { _ =>
      val k = kc.decode(in)
      val v = vc.decode(in)
      (k, v)
    }:_*)
  }

  def encode(ts: m.Map[K, V], out: OutputStream): Unit = {
    lc.encode(ts.size, out)
    ts.foreach { case (k, v) =>
      kc.encode(k, out)
      vc.encode(v, out)
    }
  }
}

sealed trait BaseCoders {
  // TODO: support all primitive types
  // BigDecimalCoder
  // BigIntegerCoder
  // BitSetCoder
  // BooleanCoder
  // ByteStringCoder

  // DurationCoder
  // InstantCoder

  // TableRowJsonCoder

  // implicit def traversableCoder[T](implicit c: Coder[T]): Coder[TraversableOnce[T]] = ???
  implicit def seqCoder[T: Coder]: Coder[Seq[T]] =
     Coder.transform(Coder[T]){ bc => Coder.beam(new SeqCoder[T](bc)) }

  // TODO: proper chunking implementation
  implicit def iterableCoder[T: Coder]: Coder[Iterable[T]] =
    Coder.transform(Coder[T]){ bc => Coder.beam(new IterableCoder[T](bc)) }

  // implicit def throwableCoder: Coder[Throwable] = ???

  // specialized coder. Since `::` is a case class, Magnolia would derive an incorrect one...
  implicit def listCoder[T: Coder]: Coder[List[T]] =
    Coder.transform(Coder[T]){ bc => Coder.beam(new ListCoder[T](bc)) }

  // implicit def vectorCoder[T: Coder]: Coder[Vector[T]] = new VectorCoder[T]
  implicit def arraybufferCoder[T: Coder]: Coder[m.ArrayBuffer[T]] =
    Coder.transform(Coder[T]){ bc => Coder.beam(new ArrayBufferCoder[T](bc)) }

  // implicit def bufferCoder[T: Coder]: Coder[scala.collection.mutable.Buffer[T]] = ???
  implicit def arrayCoder[T: Coder : ClassTag]: Coder[Array[T]] =
    Coder.transform(Coder[T]){ bc => Coder.beam(new ArrayCoder[T](bc)) }

  implicit def arrayByteCoder: Coder[Array[Byte]] = Coder.beam(ByteArrayCoder.of())

  implicit def mutableMapCoder[K: Coder, V: Coder]: Coder[m.Map[K, V]] =
    Coder.transform(Coder[K]){ kc =>
      Coder.transform(Coder[V]){ vc =>
        Coder.beam(new MutableMapCoder[K, V](kc, vc))
      }
    }

  implicit def mapCoder[K: Coder, V: Coder]: Coder[Map[K, V]] =
    Coder.transform(Coder[K]){ kc =>
      Coder.transform(Coder[V]){ vc =>
        Coder.beam(new MapCoder[K, V](kc, vc))
      }
    }

  // implicit def sortedSetCoder[T: Coder]: Coder[scala.collection.SortedSet[T]] = ???
  // implicit def enumerationCoder[E <: Enumeration]: Coder[E#Value] = ???
}

trait Implicits
//   with FromSerializable
  extends BaseCoders
  with FromBijection
  with AvroCoders
//   with ProtobufCoders
  with JavaCoders
  with AlgebirdCoders
  with Serializable

final object Implicits extends Implicits