/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.play.bootstrap.metrics

import java.io.StringWriter
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}

import ch.qos.logback.classic
import com.codahale.metrics.{Metric, MetricRegistry, MetricSet, SharedMetricRegistries}
import com.codahale.metrics.json.MetricsModule
import com.codahale.metrics.jvm.{GarbageCollectorMetricSet, JvmAttributeGaugeSet, MemoryUsageGaugeSet, ThreadStatesGaugeSet}
import com.codahale.metrics.logback.InstrumentedAppender
import com.fasterxml.jackson.databind.{ObjectWriter, ObjectMapper}
import play.api.{Logger, Configuration}
import play.api.inject.ApplicationLifecycle

import scala.concurrent.Future


trait Metrics {
  def defaultRegistry: MetricRegistry
  def toJson: String
}

@Singleton
class MetricsImpl @Inject() (lifecycle: ApplicationLifecycle, configuration: Configuration) extends Metrics {

  private val innerLogger: Logger = Logger(classOf[MetricsImpl])

  val validUnits = Set("NANOSECONDS", "MICROSECONDS", "MILLISECONDS", "SECONDS", "MINUTES", "HOURS", "DAYS")

  val registryName     : String  = configuration.get[String]("metrics.name")
  val rateUnit         : String  = configuration.getAndValidate[String]("metrics.rateUnit", validUnits)
  val durationUnit     : String  = configuration.getAndValidate[String]("metrics.durationUnit", validUnits)
  val showSamples      : Boolean = configuration.get[Boolean]("metrics.showSamples")
  val jvmMetricsEnabled: Boolean = configuration.get[Boolean]("metrics.jvm")
  val logbackEnabled   : Boolean = configuration.get[Boolean]("metrics.logback")

  private val mapper: ObjectMapper = new ObjectMapper()

  override def toJson: String = {
    val writer: ObjectWriter = mapper.writerWithDefaultPrettyPrinter()
    val stringWriter = new StringWriter()
    writer.writeValue(stringWriter, defaultRegistry)
    stringWriter.toString
  }

  override def defaultRegistry: MetricRegistry =
    SharedMetricRegistries.getOrCreate(registryName)

  def setupJvmMetrics(registry: MetricRegistry): Unit =
    if (jvmMetricsEnabled) {
      registry.register("jvm.attribute", new JvmAttributeGaugeSet())
      registry.register("jvm.gc"       , new GarbageCollectorMetricSet())
      registry.register("jvm.memory"   , new MemoryUsageGaugeSet())
      registry.register("jvm.threads"  , new ThreadStatesGaugeSet())
    }

  def setupLogbackMetrics(registry: MetricRegistry): Unit =
    if (logbackEnabled) {
      val appender: InstrumentedAppender = new InstrumentedAppender(registry)

      val logger: classic.Logger = innerLogger.logger.asInstanceOf[classic.Logger]
      appender.setContext(logger.getLoggerContext)
      appender.start()
      logger.addAppender(appender)
    }

  def onStart(): Unit = {
    setupJvmMetrics(defaultRegistry)
    setupLogbackMetrics(defaultRegistry)

    val module = new MetricsModule(TimeUnit.valueOf(rateUnit), TimeUnit.valueOf(durationUnit), showSamples)
    mapper.registerModule(module)
  }

  def onStop(): Unit = {
    SharedMetricRegistries.remove(registryName)
  }

  onStart()
  lifecycle.addStopHook(() => Future.successful(onStop()))
}

@Singleton
class DisabledMetrics extends Metrics {
  override lazy val defaultRegistry: MetricRegistry =
    new MetricRegistry {
      override def register[T <: Metric](name: String, metric: T): T =
        metric
      override def registerAll(metrics: MetricSet): Unit =
        ()
    }

  override lazy val toJson: String = "null"
}
