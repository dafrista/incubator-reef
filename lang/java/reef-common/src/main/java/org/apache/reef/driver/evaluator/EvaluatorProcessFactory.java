package org.apache.reef.driver.evaluator;

import org.apache.reef.annotations.audience.DriverSide;
import org.apache.reef.annotations.audience.Public;
import org.apache.reef.tang.annotations.DefaultImplementation;

/**
 * Factory to create new evaluator process setups
 */
@Public
@DriverSide
@DefaultImplementation(JVMProcessFactory.class)
public interface EvaluatorProcessFactory {
  EvaluatorProcess newEvaluatorProcess();
}
