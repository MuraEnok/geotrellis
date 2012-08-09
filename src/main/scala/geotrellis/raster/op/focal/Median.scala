package geotrellis.raster.op.focal

import scala.math._

import geotrellis._
import geotrellis.statistics._

case class Median(r:Op[Raster], f:Focus) extends Op1(r)({
  r => Result(f.handle(r, new MedianContext(r)))
})

protected[focal] class MedianContext(r:Raster) extends Context[Raster](Aggregated) {
  val d = IntArrayRasterData.ofDim(r.cols, r.rows)
  def store(col:Int, row:Int, z:Int) { d.set(col, row, z) }
  def get = Raster(d, r.rasterExtent)
  def makeCell() = new MedianCell
}

protected[focal] class MedianCell extends Cell {
  var h:Histogram = FastMapHistogram()
  def clear() { h = FastMapHistogram() }
  def add(z:Int) { h.countItem(z, 1) }
  def remove(z:Int) { h.countItem(z, -1) }
  def get() = h.getMedian()
}
