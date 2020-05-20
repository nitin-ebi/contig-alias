/*
 * Copyright 2020 EMBL - European Bioinformatics Institute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ebivariation.contigalias.dao;

import com.ebivariation.contigalias.dus.AssemblyReportReader;
import com.ebivariation.contigalias.dus.NCBIBrowser;
import com.ebivariation.contigalias.entities.AssemblyEntity;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

@Repository("ftpDao")
public class FTPClientAssemblyDaoImplement implements AssemblyDao {

    @Override
    public AssemblyEntity getAssemblyByAccession(String accession) throws IOException {
        NCBIBrowser ncbiBrowser = new NCBIBrowser();
        ncbiBrowser.connect();
        String directory = ncbiBrowser.getGenomeReportDirectory(accession);
        String path = ncbiBrowser.getPathFromRoot(directory);
        InputStream stream = ncbiBrowser.getAssemblyReportInputStream(path);
        InputStreamReader streamReader = new InputStreamReader(stream);
        AssemblyReportReader reader = new AssemblyReportReader(streamReader);
        AssemblyEntity assemblyEntity = reader.getAssemblyEntity();
        stream.close();
        streamReader.close();
        ncbiBrowser.disconnect();
        return assemblyEntity;
    }

}
