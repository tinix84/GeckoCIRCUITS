/*  This file is part of GeckoCIRCUITS. Copyright (C) ETH Zurich, Gecko-Simulations GmbH
 *
 *  GeckoCIRCUITS is free software: you can redistribute it and/or modify it under 
 *  the terms of the GNU General Public License as published by the Free Software 
 *  Foundation, either version 3 of the License, or (at your option) any later version.
 *
 *  Foobar is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE.  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  GeckoCIRCUITS.  If not, see <http://www.gnu.org/licenses/>.
 */
package ch.technokrat.gecko.geckocircuits.control.calculators;

import org.junit.Test;
import static org.junit.Assert.*;

public class SampleHoldCalculatorTest extends AbstractTwoInputsMathFunctionTest {        

    @Override
    AbstractControlCalculatable calculatorFabricTwoInputs() {
        return new SampleHoldCalculator();
    }

    @Override
    @Test
    public void testInputTrueTrue() {
        double fistVal = getValue(3,1);
        assertWithTol(3, fistVal);
        double secVal = getValue(4,1);
        assertWithTol(4, secVal);
    }

    @Override
    @Test
    public void testInputTrueFalse() {
        double fistVal = getValue(3,1);
        assertWithTol(3, fistVal);
        double secVal = getValue(4,0);
        assertWithTol(fistVal, secVal);
        double thirdVal = getValue(2,1);
        assertWithTol(2, thirdVal);
    }

    @Override
    public void testInputFalseFalse() {
        // no error possible!
    }

    
    
}
