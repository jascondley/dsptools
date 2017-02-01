// See LICENSE for license details.

package dsptools.numbers

import chisel3._
import chisel3.util.{ShiftRegister, Cat}
import dsptools.{hasContext, DspContext, Grow, Wrap, Saturate, DspException}
import chisel3.experimental.FixedPoint

import scala.language.implicitConversions

/**
  * Defines basic math functions for SInt
  */
trait SIntRing extends Any with Ring[SInt] with hasContext {
  def zero: SInt = 0.S
  def one: SInt = 1.S
  def plus(f: SInt, g: SInt): SInt = {
    // TODO: Saturating mux should be outside of ShiftRegister
    val sum = context.overflowType match {
      case Grow => f +& g
      case Wrap => f +% g
      case _ => throw DspException("Saturating add hasn't been implemented")
    }
    ShiftRegister(sum, context.numAddPipes)
  }
  override def minus(f: SInt, g: SInt): SInt = {
    val diff = context.overflowType match {
      case Grow => f -& g
      case Wrap => f -% g
      case _ => throw DspException("Saturating subtractor hasn't been implemented")
    }
    ShiftRegister(diff, context.numAddPipes)
  }
  def negate(f: SInt): SInt = minus(0.S, f)
  def times(f: SInt, g: SInt): SInt = {
    // TODO: Overflow via ranging in FIRRTL?
    ShiftRegister(f * g, context.numMulPipes)
  }  
}

trait SIntOrder extends Any with Order[SInt] with hasContext {
  override def compare(x: SInt, y: SInt): ComparisonBundle = {
    ComparisonHelper(x === y, x < y)
  }
  override def eqv(x: SInt, y: SInt): Bool = x === y
  override def neqv(x: SInt, y:SInt): Bool = x =/= y
  override def lt(x: SInt, y: SInt): Bool = x < y
  override def lteqv(x: SInt, y: SInt): Bool = x <= y
  override def gt(x: SInt, y: SInt): Bool = x > y
  override def gteqv(x: SInt, y: SInt): Bool = x >= y
  // min, max depends on lt, gt & mux
}

trait SIntSigned extends Any with Signed[SInt] with hasContext {
  def signum(a: SInt): ComparisonBundle = {
    ComparisonHelper(a === 0.S, a < 0.S)
  }
  override def isSignZero(a: SInt): Bool = a === 0.S
  override def isSignNegative(a:SInt): Bool = {
    if (a.widthKnown) a(a.getWidth-1)
    else a < 0.S
  }
  // isSignPositive, isSignNonZero, isSignNonPositive, isSignNonNegative derived from above (!)
  // abs requires ring (for overflow) so overridden later
}

trait SIntIsReal extends Any with IsIntegral[SInt] with SIntOrder with SIntSigned with hasContext {
  // In IsIntegral: ceil, floor, round, truncate (from IsReal) already defined as itself; 
  // isWhole always true
  // -5, -3, -1, 1, 3, 5, etc.
  def isOdd(a: SInt): Bool = a(0)
  // isEven derived from isOdd
  // Note: whatever Chisel does -- double check it's what you expect when using it
  // Generally better to use your own mod if you know input bounds
  def mod(a: SInt, b: SInt): SInt = a % b
}

trait ConvertableToSInt extends ConvertableTo[SInt] with hasContext {
  // Note: Double converted to Int via round first!
  def fromShort(n: Short): SInt = fromInt(n.toInt)
  def fromByte(n: Byte): SInt = fromInt(n.toInt)
  def fromInt(n: Int): SInt = fromBigInt(BigInt(n))
  def fromFloat(n: Float): SInt = fromDouble(n.toDouble)
  def fromBigDecimal(n: BigDecimal): SInt = fromDouble(n.doubleValue)
  def fromLong(n: Long): SInt = fromBigInt(BigInt(n))
  def fromType[B](n: B)(implicit c: ConvertableFrom[B]): SInt = fromBigInt(c.toBigInt(n))
  def fromBigInt(n: BigInt): SInt = n.S
  def fromDouble(n: Double): SInt = n.round.toInt.S  
  // Second argument needed for fixed pt binary point (unused here)
  override def fromDouble(d: Double, a: SInt): SInt = fromDouble(d)
  override def fromDoubleWithFixedWidth(d: Double, a: SInt): SInt = {
    require(a.widthKnown, "SInt width not known!")
    val intVal = d.round.toInt  
    val intBits = BigInt(intVal).bitLength + 1
    require(intBits <= a.getWidth, "Lit can't fit in prototype SInt bitwidth")
    intVal.asSInt(a.getWidth.W)
  } 
}

trait ConvertableFromSInt extends ChiselConvertableFrom[SInt] with hasContext {
  def intPart(a: SInt): SInt = a
  // Converts to FixedPoint with 0 fractional bits (Note: proto only used for real)
  override def asFixed(a: SInt): FixedPoint = a.asFixedPoint(0.BP)
  def asFixed(a: SInt, proto: FixedPoint): FixedPoint = asFixed(a)
  // Converts to (signed) DspReal
  def asReal(a: SInt): DspReal = DspReal(a)
}

trait BinaryRepresentationSInt extends BinaryRepresentation[SInt] with hasContext {
  def shl(a: SInt, n: Int): SInt = a << n
  def shl(a: SInt, n: UInt): SInt = a << n
  // Note: This rounds to negative infinity (smallest abs. value for negative #'s is -1)
  def shr(a: SInt, n: Int): SInt = a >> n
  def shr(a: SInt, n: UInt): SInt = a >> n
  // mul2 consistent with shl
  // signBit relies on Signed, div2 relies on ChiselConvertableFrom
 }

trait SIntInteger extends SIntRing with SIntIsReal with ConvertableToSInt with 
    ConvertableFromSInt with BinaryRepresentationSInt with IntegerBits[SInt] with hasContext {
  def signBit(a: SInt): Bool = isSignNegative(a)
  // fromSInt also included in Ring
  override def fromInt(n: Int): SInt = super[ConvertableToSInt].fromInt(n)
  // Overflow only on most negative
  def abs(a: SInt): SInt = Mux(isSignNegative(a), super[SIntRing].minus(0.S, a), a)
  // Rounds result to nearest int (half up) for more math-y division
  override def div2(a: SInt, n: Int): SInt = a.widthOption match {
    // If shifting more than width, guaranteed to be closer to 0
    case Some(w) if n > w => 0.S
    // TODO: Is this too conservative?, Include more rounding mode options?
    case _ => {
      import dsptools.numbers.implicits._
      asFixed(a).div2(n).round().asSInt
    }
  }
}

trait SIntImpl {
  implicit object SIntIntegerImpl extends SIntInteger
}