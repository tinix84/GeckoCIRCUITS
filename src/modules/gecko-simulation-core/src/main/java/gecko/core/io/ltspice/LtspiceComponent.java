/*  This file is part of GeckoCIRCUITS. Copyright (C) ETH Zurich, Gecko-Simulations AG
 *
 *  GeckoCIRCUITS is free software: you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation, either version 3 of the License, or (at your option) any later version.
 *
 *  GeckoCIRCUITS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE.  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  GeckoCIRCUITS.  If not, see <http://www.gnu.org/licenses/>.
 */
package gecko.core.io.ltspice;

/**
 * Represents a single component extracted from an LTspice .asc file.
 * Corresponds to a SYMBOL directive followed by SYMATTR attributes.
 */
public class LtspiceComponent {

    /** LTspice symbol type (e.g. "res", "cap", "ind", "voltage", "current", "diode") */
    private String symbolType;

    /** Instance name from SYMATTR InstName (e.g. "R1", "C2") */
    private String instName;

    /** Value string from SYMATTR Value (e.g. "1k", "100n", "SINE(0 1 1k)") */
    private String value;

    /** Second value string from SYMATTR Value2 (optional) */
    private String value2;

    /** SPICE model from SYMATTR SpiceModel (optional, for transistors/diodes) */
    private String spiceModel;

    /** X coordinate in LTspice pixel units */
    private int x;

    /** Y coordinate in LTspice pixel units */
    private int y;

    /** Orientation string from .asc file (e.g. "R0", "R90", "R180", "R270", "M0", "M90", "M180", "M270") */
    private String orientation;

    public LtspiceComponent() {
        this.orientation = "R0";
    }

    public String getSymbolType() { return symbolType; }
    public void setSymbolType(String symbolType) { this.symbolType = symbolType; }

    public String getInstName() { return instName; }
    public void setInstName(String instName) { this.instName = instName; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getValue2() { return value2; }
    public void setValue2(String value2) { this.value2 = value2; }

    public String getSpiceModel() { return spiceModel; }
    public void setSpiceModel(String spiceModel) { this.spiceModel = spiceModel; }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public String getOrientation() { return orientation; }
    public void setOrientation(String orientation) { this.orientation = orientation; }

    @Override
    public String toString() {
        return "LtspiceComponent{type='" + symbolType + "', inst='" + instName
                + "', value='" + value + "', x=" + x + ", y=" + y + ", orient='" + orientation + "'}";
    }
}
