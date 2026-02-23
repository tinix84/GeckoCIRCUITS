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
 * Represents a node label from an LTspice .asc file.
 * Corresponds to a FLAG directive that assigns a net name to a wire endpoint.
 * A flag with name "0" represents the ground net.
 */
public class LtspiceFlag {

    /** X coordinate in LTspice pixel units */
    private final int x;

    /** Y coordinate in LTspice pixel units */
    private final int y;

    /** Net name (e.g. "Vin", "Vout", "0" for ground) */
    private final String netName;

    public LtspiceFlag(int x, int y, String netName) {
        this.x = x;
        this.y = y;
        this.netName = netName;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public String getNetName() { return netName; }

    /** Returns true if this flag represents the ground net (name is "0"). */
    public boolean isGround() {
        return "0".equals(netName);
    }

    @Override
    public String toString() {
        return "LtspiceFlag{(" + x + "," + y + ") name='" + netName + "'}";
    }
}
