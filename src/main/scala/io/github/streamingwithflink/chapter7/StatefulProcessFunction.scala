package io.github.streamingwithflink.chapter7

import io.github.streamingwithflink.util.{SensorReading, SensorSource, SensorTimeAssigner}
import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.api.scala._
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.streaming.api.scala.{DataStream, KeyedStream, StreamExecutionEnvironment}
import org.apache.flink.util.Collector

object StatefulProcessFunction {

  /** main() defines and executes the DataStream program */
  def main(args: Array[String]) {

    // set up the streaming execution environment
    val env = StreamExecutionEnvironment.getExecutionEnvironment

    // checkpoint every 10 seconds
    env.getCheckpointConfig.setCheckpointInterval(10 * 1000)

    // use event time for the application
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    // configure watermark interval
    env.getConfig.setAutoWatermarkInterval(1000L)

    // ingest sensor stream
    val sensorData: DataStream[SensorReading] = env
      // SensorSource generates random temperature readings
      .addSource(new SensorSource)
      // assign timestamps and watermarks which are required for event time
      .assignTimestampsAndWatermarks(new SensorTimeAssigner)

    val keyedSensorData: KeyedStream[SensorReading, String] = sensorData.keyBy(_.id)

    val alerts: DataStream[(String, Double, Double)] = keyedSensorData
      .process(new SelfCleaningTemperatureAlertFunction(1.1))

    // print result stream to standard out
    alerts.print()

    // execute application
    env.execute("Generate Temperature Alerts")
  }
}

/**
  * The function emits an alert if the temperature measurement of a sensor increased by more than
  * a configured threshold compared to the last reading.
  *
  * The function removes the state of a sensor if it did not receive an update within 1 hour.
  *
  * @param threshold The threshold to raise an alert.
  */
class SelfCleaningTemperatureAlertFunction(val threshold: Double)
    extends ProcessFunction[SensorReading, (String, Double, Double)] {

  // the state handle object
  private var lastTempState: ValueState[Double] = _
  private var lastTimerState: ValueState[Long] = _

  override def open(parameters: Configuration): Unit = {
    // register state for last temperature
    val lastTempDescriptor = new ValueStateDescriptor[Double]("lastTemp", classOf[Double])
    lastTempState = getRuntimeContext.getState[Double](lastTempDescriptor)
    // register state for last timer
    val timestampDescriptor: ValueStateDescriptor[Long] =
      new ValueStateDescriptor[Long]("timestampState", createTypeInformation[Long])
    lastTimerState = getRuntimeContext.getState(timestampDescriptor)
  }

  override def processElement(
      in: SensorReading,
      ctx: ProcessFunction[SensorReading, (String, Double, Double)]#Context,
      out: Collector[(String, Double, Double)]) = {

    // get current watermark and add one hour
    val checkTimestamp = ctx.timerService().currentWatermark() + (3600 * 1000)
    // register new timer. only one timer per timestamp will be registered
    ctx.timerService().registerEventTimeTimer(checkTimestamp)
    // update timestamp of last timer
    lastTimerState.update(checkTimestamp)

    // fetch the last temperature from state
    val lastTemp = lastTempState.value()
    // check if we need to emit an alert
    if (lastTemp > 0.0d && (in.temperature / lastTemp) > threshold) {
      // temperature increased by more than the threshold
      out.collect((in.id, in.temperature, lastTemp))
    }

    // update lastTemp state
    this.lastTempState.update(in.temperature)
  }

  override def onTimer(
      timestamp: Long,
      ctx: ProcessFunction[SensorReading, (String, Double, Double)]#OnTimerContext,
      out: Collector[(String, Double, Double)]): Unit = {

    // get timestamp of last registered timer
    val lastTimer = lastTimerState.value()
    // check if the last registered timer fired
    if (lastTimer != null.asInstanceOf[Long] && lastTimer == timestamp) {
      // clear all state for the key
      lastTempState.clear()
      lastTimerState.clear()
    }
  }
}
