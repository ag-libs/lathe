package com.example.shapes;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

// A JUnit suite, with no test methods of its own. Not named *Test: the module's Surefire includes
// leave it out of mvn test and module runs, which would run IdShapesTest twice.
@Suite
@SelectClasses(IdShapesTest.class)
class ShapesSuite {}
