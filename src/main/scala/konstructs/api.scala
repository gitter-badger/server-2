package konstructs.api

import java.util.UUID
import scala.collection.JavaConverters._
import akka.actor.ActorRef
import com.google.gson.JsonElement
import spray.json._
import konstructs.ChunkPosition
import konstructs.protocol
import konstructs.Db

/* Data structures */
case class Block(id: Option[UUID], w: Int) {
  def withId = copy(id = Some(UUID.randomUUID))
}

object Block {
  def createWithId(w: Int): Block = {
    apply(Some(UUID.randomUUID), w)
  }
}

trait Filter[T] {
  def chain: Seq[ActorRef]
  def next(chain: Seq[ActorRef]): Filter[T]
  def next(chain: Seq[ActorRef], message: T): Filter[T]

  /** Let next plugin in chain handle the unchanged message
    */
  def continue(implicit sender: ActorRef) {
    chain.head ! next(chain = chain.tail)
  }

  /** Let next plugin in chain handle an updated message
    */
  def continueWith(newMessage: T)(implicit sender: ActorRef) {
    chain.head ! next(chain = chain.tail, message = newMessage)
  }

  /** Skip all other plugins in chain, but let the server process the
    * unchanged message
    */
  def skip(implicit sender: ActorRef) {
    chain.last ! next(chain = Seq())
  }

  /** Skip all other plugins in chain, but let the server process an
    * updated message
    */
  def skipWith(newMessage: T)(implicit sender: ActorRef) {
    chain.last ! next(chain = Seq(), message = newMessage)
  }

  /** Drop the message
    * (this is a no-op since the message is dropped if continue or
    * skip is not called)
    */
  def drop() {}
}

case class Position(x: Int, y: Int, z: Int) {
  def +(p: Position) = Position(x + p.x, y + p.y, z + p.z)
  def -(p: Position) = Position(x - p.x, y - p.y, z - p.z)
}

object Position {
  def apply(pos: protocol.Position): Position =
    apply(math.round(pos.x), math.round(pos.y), math.round(pos.z))
  def apply(chunk: ChunkPosition, x: Int, y: Int, z: Int): Position =
    Position(
      chunk.p * Db.ChunkSize + x,
      chunk.k * Db.ChunkSize + y,
      chunk.q * Db.ChunkSize + z
    )
}

case class Stack(blocks: java.util.List[Block]) {
  def w = blocks.asScala.headOption.map(_.w).getOrElse(0)
  def size = blocks.size
  def room = Stack.MaxSize - size
  def isEmpty = blocks.isEmpty
  def isFull = blocks.size == Stack.MaxSize
  def head = blocks.asScala.head
  def headOption = blocks.asScala.headOption
  def tail = Stack(blocks.asScala.tail.asJava)
  def take(n: Int) = Stack(blocks.asScala.take(n).asJava)
  def drop(n: Int) = {
    val left = blocks.asScala.drop(n)
    if(left.size != 0) {
      Stack(left.asJava)
    } else {
      Stack.Empty
    }
  }
  def acceptsStack(stack: Stack): Boolean = isEmpty || (stack.w == w && !isFull)
  def accepts(block: Block): Boolean = isEmpty || (block.w == w && !isFull)
  def acceptStack(stack: Stack): Option[(Stack, Stack)] = if(acceptsStack(stack)) {
    val r = room
    val newBlocks = blocks.asScala ++ stack.take(r).blocks.asScala
    Some((Stack(newBlocks.asJava), stack.drop(r)))
  } else {
    None
  }
  def accept(block: Block): Option[Stack] = if(accepts(block)) {
    val newBlocks = blocks.asScala :+ block
    Some(Stack(newBlocks.asJava))
  } else {
    None
  }
}

object Stack {
  def fromSeq(blocks: Seq[Block]): Stack =
    apply(blocks.toList.asJava)
  def fromBlock(block: Block): Stack =
    apply(List(block).asJava)
  val Empty = Stack(List.empty[Block].asJava)
  val MaxSize = 64
}

case class Inventory(stacks: java.util.List[Stack]) {
  def isEmpty = !stacks.asScala.exists(!_.isEmpty)

  def withoutSlot(slot: Int) = {
    val newStacks = stacks.asScala.clone
    newStacks(slot) = Stack.Empty
    copy(newStacks.asJava)
  }

  def withSlot(slot: Int, stack: Stack) = {
    val newStacks = stacks.asScala.clone
    newStacks(slot) = stack
    copy(newStacks.asJava)
  }

  def stackOption(slot: Int): Option[Stack] = {
    val stack = stacks.get(slot)
    if(stack.isEmpty) {
      None
    } else {
      Some(stack)
    }
  }

  def blockHeadOption(slot: Int): Option[Block] = {
    stackOption(slot) flatMap { stack =>
      stack.headOption
    }
  }

  def stackTail(slot: Int): Inventory = {
    val stack = stacks.get(slot)
    withSlot(slot, stack.tail)
  }

  def accept(block: Block): Option[Inventory] = {
    stacks.asScala.zipWithIndex.find(_._1.accepts(block)) flatMap {
      case (stack, slot) =>
        stack.accept(block).map(withSlot(slot, _))
    }
  }

  def accept(stack: Stack): Option[(Inventory, Stack)] = {
    stacks.asScala.zipWithIndex.find(_._1.acceptsStack(stack)) match {
      case Some((stackWithRoom, slot)) =>
        stackWithRoom.acceptStack(stack).flatMap {
          case (newStack, Stack.Empty) =>
            Some((withSlot(slot, newStack), Stack.Empty))
          case (newStack, left) =>
            val i = withSlot(slot, newStack)
            i.accept(left)
        }
      case None =>
        Some(this, stack)
    }
  }

  def removeStack(stack: Stack): Inventory = {
    stacks.asScala.zipWithIndex.find(_._1.take(stack.size) == stack) match {
      case Some((s, slot)) =>
        withSlot(slot, s.drop(stack.size))
      case None =>
        this
    }
  }

  def moveSlot(from: Int, to: Int): Inventory = {
    val fromStack = stacks.get(from)
    val toStack = stacks.get(to)
    withSlot(from, toStack).withSlot(to, fromStack)
  }

  def pattern(view: InventoryView): Option[Pattern] = {
    if(isEmpty) {
      None
    } else {
      val rows = stacks.asScala.grouped(view.columns).toSeq.dropWhile { stacks =>
        !stacks.exists(!_.isEmpty)
      }.reverse.dropWhile { stacks =>
        !stacks.exists(!_.isEmpty)
      } reverse

      val shortestEmptyPrefix = rows.map { stacks =>
        stacks.takeWhile(_.isEmpty).size
      } min
      val shortestEmptySuffix = rows.map { stacks =>
        stacks.reverse.takeWhile(_.isEmpty).size
      } min

      val columns = view.columns - shortestEmptyPrefix - shortestEmptySuffix
      val stackArray = new Array[Stack](rows.size * columns)

      for(i <- 0 until rows.size) {
        val stacks = rows(i).drop(shortestEmptyPrefix).dropRight(shortestEmptySuffix)

        for(j <- 0 until stacks.size) {
          stackArray(i*columns + j) = stacks(j)
        }
      }
      val p = Pattern(stackArray.toList.asJava, rows.size, columns)
      Some(p)
    }
  }

  def removePattern(pattern: Pattern): Inventory = {
    var n = this
    for(stack <- pattern.stacks.asScala) {
      n = n.removeStack(stack)
    }
    n
  }
}

object Inventory {
  def createEmpty(dimension: Int): Inventory =
    apply(List.fill(dimension)(Stack.Empty).asJava)
}

case class InventoryView(rowOffset: Int, columnOffset: Int, rows: Int, columns: Int) {
  import View._
  def translate(pos: Int): Int = {
    val r = pos / Columns
    val c = pos % Columns
    val row = r - rowOffset
    val column = c - columnOffset
    row * columns + column
  }

  def contains(pos: Int): Boolean = {
    val row = pos / Columns
    val col = pos % Columns
    row >= rowOffset && row < rowOffset + rows && col >= columnOffset && col < columnOffset + columns
  }
}

case class View(items: Map[Int, Option[Stack]]) {
  import View._

  def add(inventoryView: InventoryView, inventory: Inventory): View = {
    View(items ++ (for(
      row <- 0 until inventoryView.rows;
      column <- 0 until inventoryView.columns
    ) yield {
      val r = row + inventoryView.rowOffset
      val c = column + inventoryView.columnOffset
      ((r * Columns + c) -> Some(inventory.stacks.get(row * inventoryView.columns + column)))
    }))
  }

}

object View {
  val Columns = 16
  val Empty = View((for(i <- 0 until 256) yield {
    i -> None
  }).toMap)
}

/* Models for konstructing */
case class Pattern(stacks: java.util.List[Stack], rows: Int, columns: Int) {
  def complexity = stacks.asScala.map(_.size).reduceLeft(_ + _)
  def contains(p: Pattern) =
    if(p.rows == rows && p.columns == columns && stacks.size == p.stacks.size) {
      !p.stacks.asScala.zip(stacks.asScala).exists {
        case (contained, self) =>
          self.w != contained.w || contained.size > self.size
      }
    } else {
      false
    }
}

case class Konstruct(pattern: Pattern, result: Stack)


/* Messages */

/* Messages for chat */
case class Say(player: String, text: String)
case class SayFilter(chain: Seq[ActorRef], message: Say) extends Filter[Say] {
  def next(chain: Seq[ActorRef]) = copy(chain = chain)
  def next(chain: Seq[ActorRef], message: Say) = copy(chain = chain, message = message)
}
case class Said(text: String)

/* Messages for world interaction */
case class InteractPrimary(sender: ActorRef, player: String, pos: Option[Position], block: Option[Block])
case class InteractPrimaryFilter(chain: Seq[ActorRef], message: InteractPrimary) extends Filter[InteractPrimary] {
  def next(chain: Seq[ActorRef]) = copy(chain = chain)
  def next(chain: Seq[ActorRef], message: InteractPrimary) = copy(chain = chain, message = message)
}

case class InteractSecondary(sender: ActorRef, player: String, pos: Option[Position], block: Option[Block])
case class InteractSecondaryFilter(chain: Seq[ActorRef], message: InteractSecondary) extends Filter[InteractSecondary] {
  def next(chain: Seq[ActorRef]) = copy(chain = chain)
  def next(chain: Seq[ActorRef], message: InteractSecondary) = copy(chain = chain, message = message)
}

case class InteractTertiary(sender: ActorRef, player: String, pos: Option[Position], block: Option[Block])
case class InteractTertiaryFilter(chain: Seq[ActorRef], message: InteractTertiary) extends Filter[InteractTertiary] {
  def next(chain: Seq[ActorRef]) = copy(chain = chain)
  def next(chain: Seq[ActorRef], message: InteractTertiary) = copy(chain = chain, message = message)
}


case class PutBlock(pos: Position, block: Block)
case class DestroyBlock(pos: Position)
case class ReceiveStack(stack: Stack)
case class GetBlock(pos: Position)
case class GetBlockResponse(pos: Position, block: Block)
case class BlockDataUpdate(pos: Position, oldW: Int, newW: Int)

/* Manage block IDs */
case class GetOrCreateBlockId(pos: Position)
case class GetOrCreateBlockIdResponse(pos: Position, id: UUID)

/* Manage inventories */
case class CreateInventory(blockId: UUID, size: Int)
case class GetInventory(blockId: UUID)
case class GetInventoryResponse(blockId: UUID, inventory: Option[Inventory])
case class PutStack(blockId: UUID, slot: Int, stack: Stack)
case class RemoveStack(blockId: UUID, slot: Int)
case class GetStack(blockId: UUID, slot: Int)
case class GetStackResponse(blockId: UUID, slot: Int, stack: Option[Stack])
case class DeleteInventory(blockId: UUID)

/* Manage konstructing */
case class MatchPattern(pattern: Pattern)
case class PatternMatched(result: Stack)
case class KonstructPattern(pattern: Pattern)
case class PatternKonstructed(pattern: Pattern, result: Stack)
case class PatternKonstructedFilter(chain: Seq[ActorRef], message: PatternKonstructed, sender: ActorRef) extends Filter[PatternKonstructed] {
  def next(chain: Seq[ActorRef]) = copy(chain = chain, sender = sender)
  def next(chain: Seq[ActorRef], message: PatternKonstructed) = copy(chain = chain, message = message, sender = sender)
}

/* Manage player */
case class ConnectView(manager: ActorRef, view: View)
case class UpdateView(view: View)
case class PutViewStack(stack: Stack, to: Int)
case class RemoveViewStack(from: Int)
case object CloseInventory

/* Messages for binary storage */
case class StoreBinary(id: String, ns: String, data: Array[Byte])
case class LoadBinary(id: String, ns: String)
case class BinaryLoaded(id: String, data: Option[Array[Byte]])

/* Messages for JSON storage */
case class StoreJson(id: String, ns: String, data: JsValue)
case class LoadJson(id: String, ns: String)
case class JsonLoaded(id: String, data: Option[JsValue])
case class StoreGson(id: String, ns: String, data: JsonElement)
case class LoadGson(id: String, ns: String)
case class GsonLoaded(id: String, data: Option[JsonElement])

object KonstructsJsonProtocol extends DefaultJsonProtocol {
  implicit object UuidJsonFormat extends JsonFormat[UUID] {
    def write(x: UUID) = JsString(x.toString)
    def read(value: JsValue) = value match {
      case JsString(x) => UUID.fromString(x)
      case x => deserializationError("Expected UUID as JsString, but got " + x)
    }
  }

  implicit def javaListFormat[T :JsonFormat] = new RootJsonFormat[java.util.List[T]] {
    def write(list: java.util.List[T]) = JsArray(list.asScala.map(_.toJson).toVector)
    def read(value: JsValue): java.util.List[T] = value match {
      case JsArray(elements) => elements.map(_.convertTo[T])(collection.breakOut).asJava
      case x => deserializationError("Expected List as JsArray, but got " + x)
    }
  }
  implicit val blockFormat = jsonFormat2(Block.apply)
  implicit val stackFormat = jsonFormat1(Stack.apply)
  implicit val inventoryFormat = jsonFormat1(Inventory.apply)
}
