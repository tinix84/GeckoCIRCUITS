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
 * Represents a wire segment from an LTspice .asc file.
 * Corresponds to a WIRE directive with two endpoints.
 */
public class LtspiceWire {

    /** Start X coordinate in LTspice pixel units */
    private final int x1;

    /** Start Y coordinate in LTspice pixel units */
    private final int y1;

    /** End X coordinate in LTspice pixel units */
    private final int x2;

    /** End Y coordinate in LTspice pixel units */
    private final int y2;

    public LtspiceWire(int x1, int y1, int x2, int y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    public int getX1() { return x1; }
    public int getY1() { return y1; }
    public int getX2() { return x2; }
    public int getY2() { return y2; }

    @Override
    public String toString() {
        return "LtspiceWire{(" + x1 + "," + y1 + ")->(" + x2 + "," + y2 + ")}";
    }
}
