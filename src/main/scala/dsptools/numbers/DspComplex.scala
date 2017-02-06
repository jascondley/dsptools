// See LICENSE for license details.

package dsptools.numbers

import chisel3._
import chisel3.experimental.FixedPoint
import dsptools.DspException
import implicits._

object DspComplex {

  def apply[T <: Data:Ring](gen: T): DspComplex[T] = {
    if (gen.isLit()) throw DspException("Cannot use Lit in single argument DspComplex.apply")
    apply(gen, gen)
  }

  // If real, imag are literals, the literals are carried through
  // In reality, real and imag should have the same type, so should be using single argument
  // apply if you aren't trying t create a Lit
  def apply[T <: Data:Ring](real: T, imag: T): DspComplex[T] = {
    val newReal = if (real.isLit()) real else real.cloneType
    val newImag = if (imag.isLit()) imag else imag.cloneType
    new DspComplex(newReal, newImag)
  }

  // Needed for assigning to results of operations; should not use in user code for making wires
  // Assumes real, imag are not literals
  private [dsptools] def wire[T <: Data:Ring](real: T, imag: T): DspComplex[T] = {
    val result = Wire(DspComplex(real.cloneType, imag.cloneType))
    result.real := real
    result.imag := imag
    result
  }

  // Constant j
  def j[T <: Data:Ring] : DspComplex[T] = DspComplex(Ring[T].zero, Ring[T].one)

}

class DspComplex[T <: Data:Ring](val real: T, val imag: T) extends Bundle {
  // Multiply by j
  def j: DspComplex[T] = DspComplex.wire(-imag, real)
  // Divide by j
  def divj: DspComplex[T] = DspComplex.wire(imag, -real)
  // Complex conjugate
  def conj: DspComplex[T] = DspComplex.wire(real, -imag)
  // Absolute square (squared norm) = x^2 + y^2
  // Uses implicits
  def abssq: T = (real * real) + (imag * imag)

  override def cloneType: this.type = {
    new DspComplex(real.cloneType, imag.cloneType).asInstanceOf[this.type]
  }

  def underlyingType(dummy: Int = 0): String = {
    real match {
      case f: FixedPoint => "fixed"
      case r: DspReal    => "real"
      case s: SInt       => "SInt"
      case u: UInt       => "UInt"
      case _ => throw DspException(s"DspComplex found unsupported underlying type: ${real.getClass.getName}")
    }
  }
}
