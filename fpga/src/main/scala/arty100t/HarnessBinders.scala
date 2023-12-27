package chipyard.fpga.arty100t

import chisel3._
import chisel3.experimental.{DataMirror, Direction}

import freechips.rocketchip.jtag.{JTAGIO}
import freechips.rocketchip.subsystem.{PeripheryBusKey}
import freechips.rocketchip.tilelink.{TLBundle}
import freechips.rocketchip.util.{HeterogeneousBag}
import freechips.rocketchip.diplomacy.{LazyRawModuleImp}

import sifive.blocks.devices.uart.{UARTPortIO, HasPeripheryUARTModuleImp, UARTParams}
import sifive.blocks.devices.jtag.{JTAGPins, JTAGPinsFromPort}
import sifive.blocks.devices.pinctrl.{BasePin}
import sifive.fpgashells.shell._
import sifive.fpgashells.ip.xilinx._
import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.clocks._
import chipyard._
import chipyard.harness._
import chipyard.iobinders._
import testchipip.serdes._

class WithArty100TUARTTSI extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTTSIPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    ath.io_uart_bb.bundle <> port.io.uart
    ath.other_leds(1) := port.io.dropped
    ath.other_leds(9) := port.io.tsi2tl_state(0)
    ath.other_leds(10) := port.io.tsi2tl_state(1)
    ath.other_leds(11) := port.io.tsi2tl_state(2)
    ath.other_leds(12) := port.io.tsi2tl_state(3)
  }
})

class WithArty100TDDRTL extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: TLMemPort, chipId: Int) => {
    val artyTh = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val bundles = artyTh.ddrClient.out.map(_._1)
    val ddrClientBundle = Wire(new HeterogeneousBag(bundles.map(_.cloneType)))
    bundles.zip(ddrClientBundle).foreach { case (bundle, io) => bundle <> io }
    ddrClientBundle <> port.io
  }
})

// Uses PMOD JA/JB
class WithArty100TSerialTLToGPIO extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: SerialTLPort, chipId: Int) => {
    val artyTh = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("serial_tl")
    harnessIO <> port.io

    harnessIO match {
      case io: DecoupledSerialIO => {
        val clkIO = io match {
          case io: InternalSyncSerialIO => IOPin(io.clock_out)
          case io: ExternalSyncSerialIO => IOPin(io.clock_in)
        }
        val packagePinsWithPackageIOs = Seq(
          ("G13", clkIO),
          ("B11", IOPin(io.out.valid)),
          ("A11", IOPin(io.out.ready)),
          ("D12", IOPin(io.in.valid)),
          ("D13", IOPin(io.in.ready)),
          ("B18", IOPin(io.out.bits, 0)),
          ("A18", IOPin(io.out.bits, 1)),
          ("K16", IOPin(io.out.bits, 2)),
          ("E15", IOPin(io.out.bits, 3)),
          ("E16", IOPin(io.in.bits, 0)),
          ("D15", IOPin(io.in.bits, 1)),
          ("C15", IOPin(io.in.bits, 2)),
          ("J17", IOPin(io.in.bits, 3))
        )
        packagePinsWithPackageIOs foreach { case (pin, io) => {
          artyTh.xdc.addPackagePin(io, pin)
          artyTh.xdc.addIOStandard(io, "LVCMOS33")
        }}

        // Don't add IOB to the clock, if its an input
        io match {
          case io: InternalSyncSerialIO => packagePinsWithPackageIOs foreach { case (pin, io) => {
            artyTh.xdc.addIOB(io)
          }}
          case io: ExternalSyncSerialIO => packagePinsWithPackageIOs.drop(1).foreach { case (pin, io) => {
            artyTh.xdc.addIOB(io)
          }}
        }

        artyTh.sdc.addClock("ser_tl_clock", clkIO, 100)
        artyTh.sdc.addGroup(pins = Seq(clkIO))
        artyTh.xdc.clockDedicatedRouteFalse(clkIO)
      }
    }
  }
})
