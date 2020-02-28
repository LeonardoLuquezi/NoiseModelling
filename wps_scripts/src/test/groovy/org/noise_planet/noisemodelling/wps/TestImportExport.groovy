/**
 * NoiseMap is a scientific computation plugin for OrbisGIS developed in order to
 * evaluate the noise impact on urban mobility plans. This model is
 * based on the French standard method NMPB2008. It includes traffic-to-noise
 * sources evaluation and sound propagation processing.
 *
 * This version is developed at French IRSTV Institute and at IFSTTAR
 * (http://www.ifsttar.fr/) as part of the Eval-PDU project, funded by the
 * French Agence Nationale de la Recherche (ANR) under contract ANR-08-VILL-0005-01.
 *
 * Noisemap is distributed under GPL 3 license. Its reference contact is Judicaël
 * Picaut <judicael.picaut@ifsttar.fr>. It is maintained by Nicolas Fortin
 * as part of the "Atelier SIG" team of the IRSTV Institute <http://www.irstv.fr/>.
 *
 * Copyright (C) 2011 IFSTTAR
 * Copyright (C) 2011-2012 IRSTV (FR CNRS 2488)
 *
 * Noisemap is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Noisemap is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Noisemap. If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, please consult: <http://www.orbisgis.org/>
 * or contact directly:
 * info_at_ orbisgis.org
 */

package org.noise_planet.noisemodelling.wps

import org.h2gis.functions.io.shp.SHPRead
import org.noise_planet.noisemodelling.wps.Import_and_Export.Export_Table
import org.noise_planet.noisemodelling.wps.Import_and_Export.Import_File
import org.noise_planet.noisemodelling.wps.Import_and_Export.Import_Folder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Test parsing of zip file using H2GIS database
 */
class TestImportExport extends JdbcTestCase {
    Logger LOGGER = LoggerFactory.getLogger(TestImportExport.class)

    void testImportFile1() {

        String res = new Import_File().exec(connection,
                ["pathFile" : TestImportExport.getResource("receivers.shp").getPath(),
                 "inputSRID": "2154",
                 "tableName": "receivers"])

        assertEquals("The table RECEIVERS has been uploaded to database!", res)
    }

    void testImportFile2() {

        String res = new Import_File().exec(connection,
                ["pathFile" : TestImportExport.getResource("receivers.shp").getPath(),
                 "inputSRID": "4362",
                 "tableName": "receivers"])

        assertEquals("The table already has a different SRID than the one you gave.", res)
    }

    void testImportFolder() {

        File file = new File(TestImportExport.getResource("receivers.shp").getPath()).getParentFile()
        String res = new Import_Folder().exec(connection,
                ["pathFolder": file.getPath(),
                 "inputSRID" : "2154",
                 "importExt" : "shp"])


        assertEquals("The table(s) ROADS2 & ROADS & RECEIVERS & GROUND_TYPE & BUILDINGS & null has/have been uploaded to database !", res)
    }

    void testExportFile() {

        SHPRead.readShape(connection, TestDatabaseManager.getResource("receivers.shp").getPath())

        String res = new Export_Table().exec(connection,
                ["exportPath"   : TestDatabaseManager.getResource("receivers.shp").getPath(),
                 "tableToExport": "RECEIVERS"])


        assertEquals("The table RECEIVERS successfully exported !", res)
    }

}
