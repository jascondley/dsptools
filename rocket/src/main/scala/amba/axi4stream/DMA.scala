package freechips.rocketchip.amba.axi4stream

import chisel3._
import chisel3.util.{Decoupled, Queue, log2Ceil}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._

trait StreamingDMA extends LazyModule {
  val streamNode: AXI4StreamNode
}

/**
  * Bundle class for dma request descriptors
  * @param addrWidth width of address
  * @param lenWidth width of length
  */
class DMARequest
(
  val addrWidth: Int,
  val lenWidth: Int,
) extends Bundle {
  /**
    * Base address of DMA request
    */
  val baseAddress: UInt = UInt(addrWidth.W)
  /**
    * Length of DMA request, minus 1. length = 0 corresponds to a single beat
    */
  val length: UInt = UInt(lenWidth.W)
  /**
    * Default value is zero. If > 0, then do the same read or write @cycles number of times
    */
  val cycles: UInt = UInt(addrWidth.W)
  /**
    * If false, addresses considered are from @baseAddress to @baseAddress + @length
    * If true, addresses considered are @baseAddress only, in fixed mode
    */
  val fixedAddress: UInt = Bool()
}
/**
  * Companion object factory
  */
object DMARequest {
  def apply(addrWidth: Int, lenWidth: Int): DMARequest = {
    new DMARequest(addrWidth = addrWidth, lenWidth = lenWidth)
  }
}

/**
  * AXI-4 Master <=> AXI-4 Stream DMA* @param id range of IDs allowed
  * @param id id range, but only one is used
  * @param aligned aligned accesses only
  */
class StreamingAXI4DMA
(
  id: IdRange = IdRange(0, 1),
  aligned: Boolean = false,
) extends LazyModule()(Parameters.empty) {

  val streamNode = AXI4StreamIdentityNode()
  val axiNode = AXI4MasterNode(Seq(AXI4MasterPortParameters(
    Seq(AXI4MasterParameters(
      name = name,
      id = id,
      aligned = aligned,
      maxFlight = Some(2)
    )))))

  lazy val module = new LazyModuleImp(this) {
    val (in, inP) = streamNode.in.head
    val (out, outP) = streamNode.out.head
    val (axi, axiP) = axiNode.out.head

    require(inP.bundle.hasData)
    require(!inP.bundle.hasKeep)
    require(inP.bundle.n * 8 == axiP.bundle.dataBits)

    val addrWidth: Int = axiP.bundle.addrBits
    val lenWidth: Int = axiP.bundle.lenBits
    val beatLength: Int = 1 << lenWidth
    val dataWidth: Int = axiP.bundle.dataBits

    val enable = IO(Input(Bool()))
    // val softReset = IO(Input(Bool()))
    val idle = IO(Output(Bool()))

    val watchdogInterval = IO(Input(UInt(64.W)))

    val readComplete = IO(Output(Bool()))
    val readWatchdog = IO(Output(Bool()))
    val readError = IO(Output(Bool()))
    val writeComplete = IO(Output(Bool()))
    val writeWatchdog = IO(Output(Bool()))
    val writeError = IO(Output(Bool()))

    val streamToMemRequest = IO(Flipped(Decoupled(
      DMARequest(addrWidth = addrWidth, lenWidth = lenWidth)
    )))
    val streamToMemLengthRemaining = IO(Output(UInt(lenWidth.W)))

    val memToStreamRequest = IO(Flipped(Decoupled(
      DMARequest(addrWidth = addrWidth, lenWidth = lenWidth)
    )))
    val memToStreamLengthRemaining = IO(Output(UInt(lenWidth.W)))

    val reading = RegInit(false.B)
    val writing = RegInit(false.B)

    val readDescriptor = Reg(DMARequest(addrWidth = addrWidth, lenWidth = lenWidth))
    val writeDescriptor = Reg(DMARequest(addrWidth = addrWidth, lenWidth = lenWidth))

    val readBeatCounter = Reg(UInt(lenWidth.W))
    val writeBeatCounter = Reg(UInt(lenWidth.W))

    val readWatchdogCounter = RegInit(0.U(64.W))
    val writeWatchdogCounter = RegInit(0.U(64.W))

    val readAddrDone = RegInit(false.B)
    val writeAddrDone = RegInit(false.B)

    val readDataDone = RegInit(false.B)
    val writeDataDone = RegInit(false.B)

    // Set some defaults
    readComplete := false.B
    readWatchdog := readWatchdogCounter > watchdogInterval
    readError := false.B
    writeComplete := false.B
    writeWatchdog := writeWatchdogCounter > watchdogInterval
    writeError := false.B

    memToStreamRequest.ready := !reading && enable
    when (memToStreamRequest.fire()) {
      reading := true.B
      readDescriptor := memToStreamRequest.bits
      readBeatCounter := 0.U
      readAddrDone := false.B
      readDataDone := false.B
      readWatchdogCounter := 0.U
    }

    streamToMemRequest.ready := !writing && enable
    when (streamToMemRequest.fire()) {
      writing := true.B
      writeDescriptor := streamToMemRequest.bits
      writeBeatCounter := 0.U
      writeAddrDone := false.B
      writeDataDone := false.B
      writeWatchdogCounter := 0.U
    }

    when (reading) {
      readWatchdogCounter := readWatchdogCounter + 1.U
    }
    when (writing) {
      writeWatchdogCounter := writeWatchdogCounter + 1.U
    }


    val readBuffer = Module(new Queue(chiselTypeOf(axi.r.bits), beatLength))
    readBuffer.io.enq <> axi.r
    readBuffer.io.deq.ready := out.ready
    out.valid := readBuffer.io.deq.valid
    out.bits.data := readBuffer.io.deq.bits.data
    out.bits.strb := ((BigInt(1) << out.params.n) - 1).U


    val readBufferHasSpace = readBuffer.entries.U - readBuffer.io.count > readDescriptor.length
    axi.ar.valid := reading && !readAddrDone && readBufferHasSpace
    when (axi.ar.fire()) {
      readAddrDone := true.B
    }

    axi.ar.bits.addr := readDescriptor.baseAddress
    axi.ar.bits.burst := !readDescriptor.fixedAddress
    axi.ar.bits.cache := AXI4Parameters.CACHE_BUFFERABLE
    axi.ar.bits.id := id.start.U
    axi.ar.bits.len := readDescriptor.length - 1.U
    axi.ar.bits.lock := 0.U // normal access
    axi.ar.bits.prot := AXI4Parameters.PROT_INSECURE
    axi.ar.bits.size := log2Ceil((dataWidth + 7) / 8).U

    when (axi.r.fire()) {
      readBeatCounter := readBeatCounter + 1.U
      when (axi.r.bits.last || readBeatCounter >= readDescriptor.length) {
        readDataDone := true.B
      }
      readError := axi.r.bits.resp =/= AXI4Parameters.RESP_OKAY
    }

    when (readAddrDone && readDataDone) {
      readComplete := true.B
      reading := false.B
    }

    // Stream to AXI write
    val writeBuffer = Module(new Queue(in.bits.cloneType, beatLength))
    writeBuffer.io.enq <> in
    axi.w.bits.data := writeBuffer.io.deq.bits.data
    axi.w.bits.strb := writeBuffer.io.deq.bits.makeStrb
    axi.w.bits.corrupt.foreach(_ := false.B)
    axi.w.bits.last := writeBeatCounter >= writeDescriptor.length
    //writeBuffer.io.deq.bits.last // || lastBeat
    axi.w.valid := writeAddrDone && writeBuffer.io.deq.valid
    writeBuffer.io.deq.ready := axi.w.ready && writeAddrDone

    val writeBufferHasEnough = writeBuffer.io.deq.valid && writeBuffer.io.count > writeDescriptor.length
    axi.aw.valid := writing && !writeAddrDone && writeBufferHasEnough
    when (axi.aw.fire()) {
      writeAddrDone := true.B
    }

    axi.aw.bits.addr := writeDescriptor.baseAddress
    axi.aw.bits.burst := !writeDescriptor.fixedAddress
    axi.aw.bits.cache := AXI4Parameters.CACHE_BUFFERABLE
    axi.aw.bits.id := id.start.U
    axi.aw.bits.len := writeDescriptor.length - 1.U
    axi.aw.bits.lock := 0.U // normal access
    axi.aw.bits.prot := AXI4Parameters.PROT_INSECURE
    axi.aw.bits.size := log2Ceil((dataWidth + 7) / 8).U

    when (axi.w.fire()) {
      writeBeatCounter := writeBeatCounter + 1.U
      when (axi.w.bits.last) {
        writeDataDone := true.B
      }
    }

    when (writeAddrDone && writeDataDone) {
      writing := false.B
      writeComplete := true.B
    }

    axi.b.ready := true.B

    when (axi.b.fire()) {
      writeError := axi.b.bits.resp =/= AXI4Parameters.RESP_OKAY
    }

    idle :=
        !reading &&
        !writing &&
        writeBuffer.io.count === 0.U &&
        readBuffer.io.count === 0.U

  }
}

class StreamingAXI4DMAWithCSR
(
  csrAddress: AddressSet,
  beatBytes: Int = 4,
  id: IdRange = IdRange(0, 1),
  aligned: Boolean = false,
) extends LazyModule()(Parameters.empty) { outer =>

  val dma = LazyModule(new StreamingAXI4DMA(id = id, aligned = aligned))

  val axiMasterNode = dma.axiNode
  val axiSlaveNode = AXI4RegisterNode(address = csrAddress, beatBytes = beatBytes)

  val streamNode = dma.streamNode

  lazy val module = new LazyModuleImp(this) {
    val dma = outer.dma.module

    val enReg = RegInit(false.B)
    val watchdogReg = RegInit(0.U(32.W))
    val intReg = RegInit(0.U(6.W))

    dma.enable := enReg

    when (dma.readComplete) {
      intReg := intReg | 1.U
    }
    when (dma.readWatchdog) {
      intReg := intReg | 2.U
    }
    when (dma.readError) {
      intReg := intReg | 4.U
    }
    when (dma.writeComplete) {
      intReg := intReg | 8.U
    }
    when (dma.writeWatchdog) {
      intReg := intReg | 16.U
    }
    when (dma.writeError) {
      intReg := intReg | 32.U
    }

    val s2mbits = dma.streamToMemRequest.bits
    val m2sbits = dma.memToStreamRequest.bits
    val s2mBaseAddress = RegInit(0.U(s2mbits.addrWidth.W))
    val s2mLength = RegInit(0.U(s2mbits.lenWidth.W))
    val s2mCycles = RegInit(0.U(s2mbits.addrWidth.W))
    val s2mFixedAddress = RegInit(false.B)
    val m2sBaseAddress = RegInit(0.U(s2mbits.addrWidth.W))
    val m2sLength = RegInit(0.U(s2mbits.lenWidth.W))
    val m2sCycles = RegInit(0.U(s2mbits.addrWidth.W))
    val m2sFixedAddress = RegInit(false.B)

    s2mbits.baseAddress := s2mBaseAddress
    s2mbits.length := s2mLength
    s2mbits.cycles := s2mCycles
    s2mbits.fixedAddress := s2mFixedAddress

    m2sbits.baseAddress := m2sBaseAddress
    m2sbits.length := m2sLength
    m2sbits.cycles := m2sCycles
    m2sbits.fixedAddress := m2sFixedAddress

    axiSlaveNode.regmap(
      axiSlaveNode.beatBytes * 0 -> Seq(RegField(1, enReg)),
      axiSlaveNode.beatBytes * 1 -> Seq(RegField.r(1, dma.idle)),
      axiSlaveNode.beatBytes * 2 -> Seq(RegField(32, watchdogReg)),
      axiSlaveNode.beatBytes * 3 -> Seq(RegField(6, intReg)),
      axiSlaveNode.beatBytes * 4 -> Seq(RegField.w(32, s2mBaseAddress)),
      axiSlaveNode.beatBytes * 5 -> Seq(RegField.w(32, s2mLength)),
      axiSlaveNode.beatBytes * 6 -> Seq(RegField.w(32, s2mCycles)),
      axiSlaveNode.beatBytes * 7 -> Seq(RegField.w(1, s2mFixedAddress)),
      axiSlaveNode.beatBytes * 8 -> Seq(RegField(32,
        dma.streamToMemLengthRemaining,
        RegWriteFn((valid, data) => {
          dma.streamToMemRequest.valid := valid
          dma.streamToMemRequest.ready
        }))),
      axiSlaveNode.beatBytes * 9 -> Seq(RegField.w(32, m2sBaseAddress)),
      axiSlaveNode.beatBytes * 10 -> Seq(RegField.w(32, m2sLength)),
      axiSlaveNode.beatBytes * 11-> Seq(RegField.w(32, m2sCycles)),
      axiSlaveNode.beatBytes * 12 -> Seq(RegField.w(1, m2sFixedAddress)),
      axiSlaveNode.beatBytes * 13 -> Seq(RegField(32,
        dma.memToStreamLengthRemaining,
        RegWriteFn((valid, data) => {
          dma.memToStreamRequest.valid := valid
          dma.memToStreamRequest.ready
        }))),
    )
  }
}

class StreamingAXI4DMAWithMemory
(
  address: AddressSet,
  id: IdRange = IdRange(0, 1),
  aligned: Boolean = false,
  executable: Boolean = true,
  beatBytes: Int = 4,
  devName: Option[String] = None,
  errors: Seq[AddressSet] = Nil,
  wcorrupt: Boolean = false
) extends LazyModule()(Parameters.empty) {

  val dma = LazyModule(new StreamingAXI4DMA(id = id, aligned = aligned))
  val lhs = AXI4StreamIdentityNode()
  val rhs = AXI4StreamIdentityNode()
  rhs := dma.streamNode := lhs
  val streamNode = NodeHandle(lhs.inward, rhs.outward)

  val ram = AXI4RAM(
    address=address,
    executable=executable,
    beatBytes=beatBytes,
    devName = devName,
    errors = errors
  )

  ram := AXI4Fragmenter() := dma.axiNode

  lazy val module = new LazyModuleImp(this) {
    val (streamIn, inP) = lhs.in.head
    val (streamOut, outP) = rhs.out.head
    // val axiP =
    val addrWidth = dma.module.addrWidth
    val lenWidth = dma.module.lenWidth
    val beatLength = 1 << lenWidth
    val dataWidth = dma.module.dataWidth


    val io = IO(new Bundle {
      val enable = Input(Bool())
      // val softReset = IO(Input(Bool()))
      val idle = Output(Bool())

      val watchdogInterval = Input(UInt(64.W))

      val readComplete = Output(Bool())
      val readWatchdog = Output(Bool())
      val readError = Output(Bool())
      val writeComplete = Output(Bool())
      val writeWatchdog = Output(Bool())
      val writeError = Output(Bool())

      val streamToMemRequest = Flipped(Decoupled(
        DMARequest(addrWidth = addrWidth, lenWidth = lenWidth)
      ))
      val streamToMemLengthRemaining = Output(UInt(lenWidth.W))

      val memToStreamRequest = Flipped(Decoupled(
        DMARequest(addrWidth = addrWidth, lenWidth = lenWidth)
      ))
      val memToStreamLengthRemaining = Output(UInt(lenWidth.W))
    })

    dma.module.enable := io.enable
    io.idle := dma.module.enable
    dma.module.watchdogInterval := io.watchdogInterval
    io.readComplete := dma.module.readComplete
    io.readWatchdog := dma.module.readWatchdog
    io.readError := dma.module.readError
    io.writeComplete := dma.module.writeComplete
    io.writeWatchdog := dma.module.writeWatchdog
    io.writeError := dma.module.writeError

    dma.module.streamToMemRequest <> io.streamToMemRequest
    dma.module.memToStreamRequest <> io.memToStreamRequest

    io.streamToMemLengthRemaining := dma.module.streamToMemLengthRemaining
    io.memToStreamLengthRemaining := dma.module.memToStreamLengthRemaining
  }
}
