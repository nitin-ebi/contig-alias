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

package uk.ac.ebi.eva.contigalias.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import uk.ac.ebi.eva.contigalias.datasource.ENAAssemblyDataSource;
import uk.ac.ebi.eva.contigalias.datasource.NCBIAssemblyDataSource;
import uk.ac.ebi.eva.contigalias.entities.AssemblyEntity;
import uk.ac.ebi.eva.contigalias.entities.ChromosomeEntity;
import uk.ac.ebi.eva.contigalias.exception.AssemblyNotFoundException;
import uk.ac.ebi.eva.contigalias.exception.DuplicateAssemblyException;
import uk.ac.ebi.eva.contigalias.repo.AssemblyRepository;
import uk.ac.ebi.eva.contigalias.repo.ChromosomeRepository;
import uk.ac.ebi.eva.contigalias.scheduler.ChecksumSetter;

import javax.transaction.Transactional;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


@Service
public class AssemblyService {

    private final AssemblyRepository assemblyRepository;

    private final ChromosomeRepository chromosomeRepository;

    private final NCBIAssemblyDataSource ncbiDataSource;

    private final ENAAssemblyDataSource enaDataSource;

    private final ChecksumSetter checksumSetter;

    private final int BATCH_SIZE = 100;

    private final Logger logger = LoggerFactory.getLogger(AssemblyService.class);

    @Autowired
    public AssemblyService(AssemblyRepository repository, ChromosomeRepository chromosomeRepository,
                           NCBIAssemblyDataSource ncbiDataSource, ENAAssemblyDataSource enaDataSource,
                           ChecksumSetter checksumSetter) {
        this.assemblyRepository = repository;
        this.chromosomeRepository = chromosomeRepository;
        this.ncbiDataSource = ncbiDataSource;
        this.enaDataSource = enaDataSource;
        this.checksumSetter = checksumSetter;
    }

    public Optional<AssemblyEntity> getAssemblyByInsdcAccession(String insdcAccession) {
        Optional<AssemblyEntity> entity = assemblyRepository.findAssemblyEntityByInsdcAccession(insdcAccession);
        stripAssemblyFromChromosomes(entity);
        return entity;
    }

    public Optional<AssemblyEntity> getAssemblyByRefseq(String refseq) {
        Optional<AssemblyEntity> entity = assemblyRepository.findAssemblyEntityByRefseq(refseq);
        stripAssemblyFromChromosomes(entity);
        return entity;
    }

    public Page<AssemblyEntity> getAssembliesByTaxid(long taxid, Pageable request) {
        Page<AssemblyEntity> page = assemblyRepository.findAssemblyEntitiesByTaxid(taxid, request);
        page.forEach(this::stripAssemblyFromChromosomes);
        return page;
    }

    public void putAssemblyChecksumsByAccession(String accession, String md5, String trunc512) {
        Optional<AssemblyEntity> entity = assemblyRepository.findAssemblyEntityByAccession(accession);
        if (!entity.isPresent()) {
            throw new IllegalArgumentException(
                    "No assembly corresponding to accession " + accession + " found in the database");
        }
        AssemblyEntity assemblyEntity = entity.get();
        assemblyEntity.setMd5checksum(md5).setTrunc512checksum(trunc512);
        assemblyRepository.save(assemblyEntity);
    }

    public void fetchAndInsertAssembly(String accession) throws IOException {
        // check if assembly already exists in db
        Optional<AssemblyEntity> entity = assemblyRepository.findAssemblyEntityByAccession(accession);
        if (entity.isPresent()) {
            throw duplicateAssemblyInsertionException(accession, entity.get());
        }

        Optional<Path> downloadNCBIFilePathOpt = ncbiDataSource.downloadAssemblyReport(accession);
        Path downloadedNCBIFilePath = downloadNCBIFilePathOpt.orElseThrow(() -> new AssemblyNotFoundException(accession));
        Optional<Path> downloadENAFilePathOpt = enaDataSource.downloadAssemblyReport(accession);
        Path downloadedENAFilePath = downloadENAFilePathOpt.orElse(null);

        long numberOfChromosomesInFile = Files.lines(downloadedNCBIFilePath).filter(line -> !line.startsWith("#")).count();
        logger.info("Number of chromosomes in assembly (" + accession + "): " + numberOfChromosomesInFile);

        // parse file and save data
        parseFileAndInsertAssembly(downloadedNCBIFilePath, downloadedENAFilePath);
        logger.info("Successfully inserted assembly for accession " + accession);

        // submit job for retrieving and updating MD5 Checksum for assembly (asynchronously)
        checksumSetter.updateMd5CheckSumForAssemblyAsync(accession);

        Files.deleteIfExists(downloadedNCBIFilePath);
        if (downloadedENAFilePath != null) {
            Files.deleteIfExists(downloadedENAFilePath);
        }
    }

    //TODO: put it somewhere else where transaction works
    @Transactional
    public void parseFileAndInsertAssembly(Path downloadedNCBIFilePath, Path downloadedENAFilePath) throws IOException {
        AssemblyEntity assemblyEntity = ncbiDataSource.getAssemblyEntity(downloadedNCBIFilePath);
        assemblyRepository.save(assemblyEntity);

        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(downloadedNCBIFilePath.toFile()))) {
            List<String> chrLines = new ArrayList<>();
            String line;
            long chromosomesSavedTillNow = 0;
            while ((line = bufferedReader.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                chrLines.add(line);
                if (chrLines.size() == BATCH_SIZE) {
                    List<ChromosomeEntity> chromosomeEntityList = ncbiDataSource.getChromosomeEntityList(assemblyEntity, chrLines);
                    if (downloadedENAFilePath != null) {
                        addENASequenceNameToChromosomes(assemblyEntity, chromosomeEntityList, downloadedENAFilePath);
                    }
                    chromosomeRepository.saveAll(chromosomeEntityList);
                    chromosomesSavedTillNow += chromosomeEntityList.size();
                    logger.info("Number of total chromosomes saved till now : " + chromosomesSavedTillNow);

                    chrLines = new ArrayList<>();
                }
            }

            if (!chrLines.isEmpty()) {
                List<ChromosomeEntity> chromosomeEntityList = ncbiDataSource.getChromosomeEntityList(assemblyEntity, chrLines);
                if (downloadedENAFilePath != null) {
                    addENASequenceNameToChromosomes(assemblyEntity, chromosomeEntityList, downloadedENAFilePath);
                }
                chromosomeRepository.saveAll(chromosomeEntityList);
                chromosomesSavedTillNow += chromosomeEntityList.size();
                logger.info("Number of total chromosomes saved till now : " + chromosomesSavedTillNow);
            }
        }
    }

    public void addENASequenceNameToChromosomes(AssemblyEntity assemblyEntity, List<ChromosomeEntity> ncbiChromosomeList,
                                                Path downloadedENAFilePath) throws IOException {
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(downloadedENAFilePath.toFile()))) {
            List<String> chrLines = new ArrayList<>();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if (line.startsWith("accession")) {
                    continue;
                }
                chrLines.add(line);
                if (chrLines.size() == BATCH_SIZE) {
                    List<ChromosomeEntity> enaChromosomeList = enaDataSource.getChromosomeEntityList(assemblyEntity, chrLines);
                    enaDataSource.addENASequenceNames(
                            !enaChromosomeList.isEmpty() ? enaChromosomeList : Collections.emptyList(),
                            !ncbiChromosomeList.isEmpty() ? ncbiChromosomeList : Collections.emptyList()
                    );

                    chrLines = new ArrayList<>();
                }
            }
            if (!chrLines.isEmpty()) {
                List<ChromosomeEntity> enaChromosomeList = enaDataSource.getChromosomeEntityList(assemblyEntity, chrLines);
                enaDataSource.addENASequenceNames(
                        !enaChromosomeList.isEmpty() ? enaChromosomeList : Collections.emptyList(),
                        !ncbiChromosomeList.isEmpty() ? ncbiChromosomeList : Collections.emptyList()
                );
            }
        }
    }

    public void fetchAndInsertAssemblyOld(String accession) throws IOException {
        Optional<AssemblyEntity> entity = assemblyRepository.findAssemblyEntityByAccession(accession);
        if (entity.isPresent()) {
            throw duplicateAssemblyInsertionException(accession, entity.get());
        }
        Optional<AssemblyEntity> fetchAssembly = ncbiDataSource.getAssemblyByAccession(accession);
        if (!fetchAssembly.isPresent()) {
            throw new AssemblyNotFoundException(accession);
        }
        if (fetchAssembly.isPresent()) {
            AssemblyEntity assemblyEntity = fetchAssembly.get();
            enaDataSource.addENASequenceNamesToAssembly(assemblyEntity);
            if (assemblyEntity.getChromosomes() != null && assemblyEntity.getChromosomes().size() > 0) {
                insertAssembly(assemblyEntity);
                logger.info("Successfully inserted assembly for accession " + accession);
                // submit job for retrieving and updating MD5 Checksum for assembly (asynchronously)
                checksumSetter.updateMd5CheckSumForAssemblyAsync(accession);
            } else {
                logger.error("Skipping inserting assembly : No chromosome in assembly " + accession);
            }
        } else {
            logger.error("Could not get assembly from NCBI");
        }
    }

    public void retrieveAndInsertMd5ChecksumForAssembly(String assembly) {
        checksumSetter.updateMd5CheckSumForAssemblyAsync(assembly);
    }

    public Map<String, Set<String>> getMD5ChecksumUpdateTaskStatus() {
        return checksumSetter.getMD5ChecksumUpdateTaskStatus();
    }

    public Optional<AssemblyEntity> getAssemblyByAccession(String accession) {
        Optional<AssemblyEntity> entity = assemblyRepository.findAssemblyEntityByAccession(accession);
        if (entity.isPresent()) {
            stripAssemblyFromChromosomes(entity);
            return entity;
        } else {
            throw new AssemblyNotFoundException(accession);
        }
    }

    public void stripAssemblyFromChromosomes(Optional<AssemblyEntity> optional) {
        if (optional.isPresent()) {
            AssemblyEntity entity = optional.get();
            stripAssemblyFromChromosomes(entity);
        }
    }

    private void stripAssemblyFromChromosomes(AssemblyEntity assembly) {
        List<ChromosomeEntity> chromosomes = assembly.getChromosomes();
        if (chromosomes != null && chromosomes.size() > 0) {
            chromosomes.forEach(it -> it.setAssembly(null));
        } else {
            assembly.setChromosomes(Collections.emptyList());
        }
    }

    @Transactional
    public void insertAssembly(AssemblyEntity entity) {
        if (isEntityPresent(entity)) {
            throw duplicateAssemblyInsertionException(null, entity);
        } else {
            assemblyRepository.save(entity);
        }
    }


    public boolean isEntityPresent(AssemblyEntity entity) {
        String insdcAccession = entity.getInsdcAccession();
        String refseq = entity.getRefseq();
        if (insdcAccession == null && refseq == null) {
            return false;
        }
        Optional<AssemblyEntity> existingAssembly = assemblyRepository.findAssemblyEntityByInsdcAccessionOrRefseq(
                // Setting to invalid prevents finding random accessions with null GCA/GCF
                insdcAccession == null ? "##########" : insdcAccession,
                refseq == null ? "##########" : refseq);
        return existingAssembly.isPresent();
    }

    public Map<String, List<String>> fetchAndInsertAssembly(List<String> accessions) {
        Map<String, List<String>> accessionResult = new HashMap<>();
        accessionResult.put("SUCCESS", new ArrayList<>());
        accessionResult.put("FAILURE", new ArrayList<>());

        for (String accession : accessions) {
            try {
                logger.info("Started processing assembly accession : " + accession);
                this.fetchAndInsertAssembly(accession);
                accessionResult.get("SUCCESS").add(accession);
            } catch (Exception e) {
                logger.error("Exception while loading assembly for accession " + accession + e);
                accessionResult.get("FAILURE").add(accession);
            }
        }
        logger.info("Success: " + accessionResult.getOrDefault("SUCCESS", Collections.emptyList()));
        logger.info("Failure: " + accessionResult.getOrDefault("FAILURE", Collections.emptyList()));

        return accessionResult;
    }

    public void deleteAssemblyByInsdcAccession(String insdcAccession) {
        assemblyRepository.deleteAssemblyEntityByInsdcAccession(insdcAccession);
    }

    public void deleteAssemblyByRefseq(String refseq) {
        assemblyRepository.deleteAssemblyEntityByRefseq(refseq);
    }

    public void deleteAssemblyByAccession(String accession) {
        Optional<AssemblyEntity> assembly = getAssemblyByAccession(accession);
        assembly.ifPresent(this::deleteAssembly);
    }

    public void deleteAssembly(AssemblyEntity entity) {
        assemblyRepository.delete(entity);
    }

    private DuplicateAssemblyException duplicateAssemblyInsertionException(String accession, AssemblyEntity present) {
        StringBuilder exception = new StringBuilder("A similar assembly already exists!");
        if (accession != null) {
            exception.append("\n");
            exception.append("Assembly trying to insert:");
            exception.append("\t");
            exception.append(accession);
        }
        if (present != null) {
            exception.append("\n");
            exception.append("Assembly already present");
            exception.append("\t");
            exception.append(present);
        }
        return new DuplicateAssemblyException(exception.toString());
    }

}