/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.stages.impl.feature

import com.salesforce.op.UID
import com.salesforce.op.features.types._
import com.salesforce.op.stages.base.sequence.{SequenceEstimator, SequenceModel}
import com.salesforce.op.utils.spark.SequenceAggregators
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.param.{BooleanParam, DoubleParam}
import org.apache.spark.sql.Dataset

import scala.reflect.runtime.universe.TypeTag

/**
 * Converts a sequence of Nullable Numeric features into a vector feature.
 * Can choose to fill null values with the mean or a constant
 *
 * @param uid uid for instance
 */
class RealVectorizer[T <: Real]
(
  uid: String = UID[RealVectorizer[_]],
  operationName: String = "vecReal"
)(implicit tti: TypeTag[T], ttiv: TypeTag[T#Value])
  extends SequenceEstimator[T, OPVector](operationName = operationName, uid = uid)
    with VectorizerDefaults with TrackNullsParam {

  final val fillValue = new DoubleParam(this, "fillValue", "default value for FillWithConstant")
  setDefault(fillValue, 0.0)

  final val withConstant = new BooleanParam(this, "fillWithConstant",
    "boolean to check if filling the nulls with a constant value")

  setDefault(withConstant, true)


  def setFillWithConstant(value: Double): this.type = {
    set(fillValue, value)
    set(withConstant, true)
  }

  def setFillWithMean: this.type = {
    set(withConstant, false)
  }

  private def constants(): Seq[Double] = {
    val size = getInputFeatures().length
    val defValue = $(fillValue)
    val constants = List.fill(size)(defValue)
    constants
  }

  private def means(dataset: Dataset[Seq[T#Value]]): Seq[Double] = {
    val size = getInputFeatures().length
    val means = dataset.select(SequenceAggregators.MeanSeqNullNum(size = size).toColumn).first()
    means
  }

  def fitFn(dataset: Dataset[Seq[T#Value]]): SequenceModel[T, OPVector] = {
    if ($(trackNulls)) setMetadata(vectorMetadataWithNullIndicators.toMetadata)

    val fillValues = if ($(withConstant)) constants() else means(dataset)
    new RealVectorizerModel[T](
      fillValues = fillValues, trackNulls = $(trackNulls), operationName = operationName, uid = uid)
  }

}

final class RealVectorizerModel[T <: Real] private[op]
(
  val fillValues: Seq[Double],
  val trackNulls: Boolean,
  operationName: String,
  uid: String
)(implicit tti: TypeTag[T])
  extends SequenceModel[T, OPVector](operationName = operationName, uid = uid)
    with VectorizerDefaults {

  def transformFn: Seq[T] => OPVector = row => {
    val replaced =
      if (!trackNulls) {
        row.zip(fillValues).
          map { case (r, m) => r.value.getOrElse(m) }
      }
      else {
        row.zip(fillValues).
          flatMap { case (r, m) => r.value.getOrElse(m) :: booleanToDouble(r.isEmpty) :: Nil }
      }
    Vectors.dense(replaced.toArray).toOPVector
  }

}
