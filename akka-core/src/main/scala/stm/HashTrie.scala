/**
 Copyright (c) 2007-2008, Rich Hickey
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:

 * Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above
   copyright notice, this list of conditions and the following
   disclaimer in the documentation and/or other materials provided
   with the distribution.

 * Neither the name of Clojure nor the names of its contributors
   may be used to endorse or promote products derived from this
   software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 POSSIBILITY OF SUCH DAMAGE.
 */

package se.scalablesolutions.akka.stm

trait PersistentDataStructure

/**
 * A clean-room port of Rich Hickey's persistent hash trie implementation from
 * Clojure (http://clojure.org).  Originally presented as a mutable structure in
 * a paper by Phil Bagwell.
 *
 * @author Daniel Spiewak
 * @author Rich Hickey
 */
@serializable
final class HashTrie[K, +V] private (root: Node[K, V]) extends Map[K, V] with PersistentDataStructure {
  override lazy val size = root.size

  def this() = this(new EmptyNode[K])

  def get(key: K) = root(key, key.hashCode)

  override def +[A >: V](pair: (K, A)) = update(pair._1, pair._2)

  override def update[A >: V](key: K, value: A) = new HashTrie(root(0, key, key.hashCode) = value)

  def -(key: K) = new HashTrie(root.remove(key, key.hashCode))

  def iterator = root.elements

  def empty[A]: HashTrie[K, A] = new HashTrie(new EmptyNode[K])

  def diagnose = root.toString
}

object HashTrie {
  def apply[K, V](pairs: (K, V)*) = pairs.foldLeft(new HashTrie[K, V]) { _ + _ }

  def unapplySeq[K, V](map: HashTrie[K, V]) = map.toSeq
}

// ============================================================================
// nodes

@serializable
private[stm] sealed trait Node[K, +V] {
  val size: Int

  def apply(key: K, hash: Int): Option[V]

  def update[A >: V](shift: Int, key: K, hash: Int, value: A): Node[K, A]

  def remove(key: K, hash: Int): Node[K, V]

  def elements: Iterator[(K, V)]
}

@serializable
private[stm] class EmptyNode[K] extends Node[K, Nothing] {
  val size = 0

  def apply(key: K, hash: Int) = None

  def update[V](shift: Int, key: K, hash: Int, value: V) = new LeafNode(key, hash, value)

  def remove(key: K, hash: Int) = this

  lazy val elements = new Iterator[(K, Nothing)] {
    val hasNext = false

    val next = null
  }
}

private[stm] abstract class SingleNode[K, +V] extends Node[K, V] {
  val hash: Int
}


private[stm] class LeafNode[K, +V](key: K, val hash: Int, value: V) extends SingleNode[K, V] {
  val size = 1

  def apply(key: K, hash: Int) = if (this.key == key) Some(value) else None

  def update[A >: V](shift: Int, key: K, hash: Int, value: A) = {
    if (this.key == key) {
      if (this.value == value) this else new LeafNode(key, hash, value)
    } else if (this.hash == hash) {
      new CollisionNode(hash, this.key -> this.value, key -> value)
    } else {
      BitmappedNode(shift)(this, key, hash, value)
    }
  }

  def remove(key: K, hash: Int) = if (this.key == key) new EmptyNode[K] else this

  def elements = new Iterator[(K, V)] {
    var hasNext = true

    def next = {
      hasNext = false
      (key, value)
    }
  }

  override def toString = "LeafNode(" + key + " -> " + value + ")"
}


private[stm] class CollisionNode[K, +V](val hash: Int, bucket: List[(K, V)]) extends SingleNode[K, V] {
  lazy val size = bucket.length

  def this(hash: Int, pairs: (K, V)*) = this(hash, pairs.toList)

  def apply(key: K, hash: Int) = {
    for {
      (_, v) <- bucket find { case (k, _) => k == key }
    } yield v
  }

  override def update[A >: V](shift: Int, key: K, hash: Int, value: A): Node[K, A] = {
    if (this.hash == hash) {
      var found = false

      val newBucket = for ((k, v) <- bucket) yield {
        if (k == key) {
          found = true
          (key, value)
        } else (k, v)
      }

      new CollisionNode(hash, if (found) newBucket else (key, value) :: bucket)
    } else {
      BitmappedNode(shift)(this, key, hash, value)
    }
  }

  override def remove(key: K, hash: Int) = {
    val newBucket = bucket filter { case (k, _) => k != key }

    if (newBucket.length == bucket.length) this else {
      if (newBucket.length == 1) {
        val (key, value) = newBucket.head
        new LeafNode(key, hash, value)
      } else new CollisionNode(hash, newBucket)
    }
  }

  def iterator = bucket.iterator

  def elements = bucket.iterator

  override def toString = "CollisionNode(" + bucket.toString + ")"
}

private[stm] class BitmappedNode[K, +V](shift: Int)(table: Array[Node[K, V]], bits: Int) extends Node[K, V] {
  lazy val size = {
    val sizes = for {
      n <- table
      if n != null
    } yield n.size

    sizes.foldLeft(0) { _ + _ }
  }

  def apply(key: K, hash: Int) = {
    val i = (hash >>> shift) & 0x01f
    val mask = 1 << i

    if ((bits & mask) == mask) table(i)(key, hash) else None
  }

  override def update[A >: V](levelShift: Int, key: K, hash: Int, value: A): Node[K, A] = {
    val i = (hash >>> shift) & 0x01f
    val mask = 1 << i

    if ((bits & mask) == mask) {
      val node = (table(i)(shift + 5, key, hash) = value)

      if (node == table(i)) this else {
        val newTable = new Array[Node[K, A]](table.length)
        Array.copy(table, 0, newTable, 0, table.length)

        newTable(i) = node

        new BitmappedNode(shift)(newTable, bits)
      }
    } else {
      val newTable = new Array[Node[K, A]](math.max(table.length, i + 1))
      Array.copy(table, 0, newTable, 0, table.length)

      newTable(i) = new LeafNode(key, hash, value)

      val newBits = bits | mask
      if (newBits == ~0) {
        new FullNode(shift)(newTable)
      } else {
        new BitmappedNode(shift)(newTable, newBits)
      }
    }
  }

  def remove(key: K, hash: Int) = {
    val i = (hash >>> shift) & 0x01f
    val mask = 1 << i

    if ((bits & mask) == mask) {
      val node = table(i).remove(key, hash)

      if (node == table(i)) {
        this
      } else if (node.isInstanceOf[EmptyNode[_]]) {
        if (size == 1) new EmptyNode[K] else {
          val adjustedBits = bits ^ mask
          val log = math.log(adjustedBits) / math.log(2)

          if (log.toInt.toDouble == log) {      // last one
            table(log.toInt)
          } else {
            val newTable = new Array[Node[K, V]](table.length)
            Array.copy(table, 0, newTable, 0, newTable.length)

            newTable(i) = null

            new BitmappedNode(shift)(newTable, adjustedBits)
          }
        }
      } else {
        val newTable = new Array[Node[K, V]](table.length)
        Array.copy(table, 0, newTable, 0, table.length)

        newTable(i) = node

        new BitmappedNode(shift)(newTable, bits)
      }
    } else this
  }

  def elements = {
    table.foldLeft(emptyElements) { (it, e) =>
      if (e eq null) it else it ++ e.elements
    }
  }

  override def toString = "BitmappedNode(" + size + "," + table.filter(_ ne null).toList.toString + ")"

  private lazy val emptyElements: Iterator[(K, V)] = new Iterator[(K, V)] {
    val hasNext = false

    val next = null
  }
}


private[stm] object BitmappedNode {
  def apply[K, V](shift: Int)(node: SingleNode[K, V], key: K, hash: Int, value: V) = {
    val table = new Array[Node[K, V]](math.max((hash >>> shift) & 0x01f, (node.hash >>> shift) & 0x01f) + 1)

    val preBits = {
      val i = (node.hash >>> shift) & 0x01f
      table(i) = node
      1 << i
    }

    val bits = {
      val i = (hash >>> shift) & 0x01f
      val mask = 1 << i

      if ((preBits & mask) == mask) {
        table(i) = (table(i)(shift + 5, key, hash) = value)
      } else {
        table(i) = new LeafNode(key, hash, value)
      }

      preBits | mask
    }

    new BitmappedNode(shift)(table, bits)
  }
}


private[stm] class FullNode[K, +V](shift: Int)(table: Array[Node[K, V]]) extends Node[K, V] {
  lazy val size = table.foldLeft(0) { _ + _.size }

  def apply(key: K, hash: Int) = table((hash >>> shift) & 0x01f)(key, hash)

  def update[A >: V](levelShift: Int, key: K, hash: Int, value: A) = {
    val i = (hash >>> shift) & 0x01f

    val node = (table(i)(shift + 5, key, hash) = value)

    if (node == table(i)) this else {
            val newTable = new Array[Node[K, A]](32)
            Array.copy(table, 0, newTable, 0, 32)

            newTable(i) = node

            new FullNode(shift)(newTable)
     }
  }

  def remove(key: K, hash: Int) = {
    val i = (hash >>> shift) & 0x01f
    val mask = 1 << i

    val node = table(i).remove(key, hash)

    if (node == table(i)) this else {
      val newTable = new Array[Node[K, V]](32)
      Array.copy(table, 0, newTable, 0, 32)

      if (node.isInstanceOf[EmptyNode[_]]) {
        newTable(i) = null
        new BitmappedNode(shift)(newTable, ~mask)
      } else {
        newTable(i) = node
        new FullNode(shift)(newTable)
      }
    }
  }

  def elements = table.foldLeft(emptyElements) { _ ++ _.elements }

  override def toString = "FullNode(" + table.foldLeft("") { _.toString + ", " + _.toString } + ")"

  private lazy val emptyElements: Iterator[(K, V)] = new Iterator[(K, V)] {
    val hasNext = false

    val next = null
  }
}
