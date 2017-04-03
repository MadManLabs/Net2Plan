/*
 * ******************************************************************************
 *  * Copyright (c) 2017 Pablo Pavon-Marino.
 *  * All rights reserved. This program and the accompanying materials
 *  * are made available under the terms of the GNU Lesser Public License v3.0
 *  * which accompanies this distribution, and is available at
 *  * http://www.gnu.org/licenses/lgpl.html
 *  *
 *  * Contributors:
 *  *     Pablo Pavon-Marino - Jose-Luis Izquierdo-Zaragoza, up to version 0.3.1
 *  *     Pablo Pavon-Marino - from version 0.4.0 onwards
 *  *     Pablo Pavon Marino - Jorge San Emeterio Villalain, from version 0.4.1 onwards
 *  *****************************************************************************
 */

package com.net2plan.gui.plugins;

import org.junit.BeforeClass;

/**
 * @author Jorge San Emeterio Villalain
 * @date 3/04/17
 */
public class GUINetworkDesignTest
{
    private GUINetworkDesign callback;

    @BeforeClass
    public void setUp()
    {
        this.callback = new GUINetworkDesign();
    }
}