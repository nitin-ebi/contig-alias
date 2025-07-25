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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import uk.ac.ebi.eva.contigalias.entities.AssemblyEntity;
import uk.ac.ebi.eva.contigalias.entities.ChromosomeEntity;
import uk.ac.ebi.eva.contigalias.repo.ChromosomeRepository;

import javax.transaction.Transactional;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;

import static uk.ac.ebi.eva.contigalias.controller.contigalias.ContigAliasController.AUTHORITY_INSDC;
import static uk.ac.ebi.eva.contigalias.controller.contigalias.ContigAliasController.AUTHORITY_REFSEQ;
import static uk.ac.ebi.eva.contigalias.controller.contigalias.ContigAliasController.NAME_ENA_TYPE;
import static uk.ac.ebi.eva.contigalias.controller.contigalias.ContigAliasController.NAME_GENBANK_TYPE;
import static uk.ac.ebi.eva.contigalias.controller.contigalias.ContigAliasController.NAME_UCSC_TYPE;

@Service
public class ChromosomeService {

    private final ChromosomeRepository repository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ChromosomeService(ChromosomeRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }


    public Page<ChromosomeEntity> getChromosomesByInsdcAccession(String insdcAccession, Pageable request) {
        Page<ChromosomeEntity> chromosomes = repository.findChromosomeEntitiesByInsdcAccessionOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(insdcAccession, request);
        return stripChromosomesAndScaffoldsFromAssembly(chromosomes);
    }

    public Page<ChromosomeEntity> getChromosomesByRefseq(String refseq, Pageable request) {
        Page<ChromosomeEntity> chromosomes = repository.findChromosomeEntitiesByRefseqOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(refseq, request);
        return stripChromosomesAndScaffoldsFromAssembly(chromosomes);
    }

    public Page<ChromosomeEntity> getChromosomesByAssemblyInsdcAccession(String asmInsdcAccession, Pageable request) {
        Page<ChromosomeEntity> chromosomes = repository.findChromosomeEntitiesByAssembly_InsdcAccessionOrderByInsdcAccessionDesc(asmInsdcAccession, request);
        return stripAssembliesFromChromosomes(chromosomes);
    }

    public List<String> getAssembliesWhereChromosomeMd5ChecksumIsNull() {
        return repository.findAssembliesWhereChromosomeMd5checksumIsNullOrEmpty();
    }

    public Page<ChromosomeEntity> getChromosomesByAssemblyInsdcAccessionWhereMd5ChecksumIsNull(String asmInsdcAccession, Pageable request) {
        Page<ChromosomeEntity> chrPage = repository.findChromosomeEntitiesByAssembly_InsdcAccessionAndMd5checksumIsNullOrEmpty(asmInsdcAccession, request);
        return chrPage;
    }

    @Transactional
    public void updateMd5ChecksumForAllChromosomeInAssembly(String assembly, List<ChromosomeEntity> chromosomeEntityList) {
        for (ChromosomeEntity chromosome : chromosomeEntityList) {
            repository.updateMd5ChecksumByInsdcAccession(assembly, chromosome.getInsdcAccession(), chromosome.getMd5checksum());
        }
    }

    @Transactional
    public void updateENASequenceNameForAllChromosomeInAssembly(String assembly, List<ChromosomeEntity> chromosomeEntityList) {
        for (ChromosomeEntity chromosome : chromosomeEntityList) {
            repository.updateENASequenceNameByInsdcAccession(assembly, chromosome.getInsdcAccession(), chromosome.getEnaSequenceName());
        }
    }

    public Page<ChromosomeEntity> getChromosomesByAssemblyRefseq(String asmRefseq, Pageable request) {
        Page<ChromosomeEntity> chromosomes = repository.findChromosomeEntitiesByAssembly_RefseqOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(asmRefseq, request);
        return stripAssembliesFromChromosomes(chromosomes);
    }

    public List<AssemblyEntity> getAssembliesByChromosomeInsdcAccession(String chrInsdcAccession) {
        Page<ChromosomeEntity> page = repository.findChromosomeEntitiesByInsdcAccessionOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(chrInsdcAccession, Pageable.unpaged());
        return extractAssembliesFromChromosomes(page);
    }

    public List<AssemblyEntity> getAssembliesByChromosomeRefseq(String chrRefseq) {
        Page<ChromosomeEntity> page = repository.findChromosomeEntitiesByRefseqOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(chrRefseq, Pageable.unpaged());
        return extractAssembliesFromChromosomes(page);
    }

    public List<AssemblyEntity> extractAssembliesFromChromosomes(Page<ChromosomeEntity> page) {
        List<AssemblyEntity> list = new LinkedList<>();
        if (page != null && page.getTotalElements() > 0) {
            for (ChromosomeEntity chromosomeEntity : page) {
                AssemblyEntity assembly = chromosomeEntity.getAssembly();
                assembly.setChromosomes(null);
                list.add(assembly);
            }
        }
        return list;
    }

    public Page<ChromosomeEntity> getChromosomesByName(String name, Pageable request) {
        Page<ChromosomeEntity> page = repository.findChromosomeEntitiesByGenbankSequenceNameOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(name, request);
        return stripChromosomesAndScaffoldsFromAssembly(page);
    }

    public Page<ChromosomeEntity> getChromosomesByNameAndAssemblyTaxid(String name, long asmTaxid, Pageable request) {
        Page<ChromosomeEntity> page = repository.findChromosomeEntitiesByGenbankSequenceNameAndAssembly_TaxidOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(name, asmTaxid, request);
        return stripChromosomesAndScaffoldsFromAssembly(page);
    }

    public Page<ChromosomeEntity> getChromosomesByNameAndAssembly(
            String name, AssemblyEntity assembly, Pageable request) {
        Page<ChromosomeEntity> page = repository.findChromosomeEntitiesByGenbankSequenceNameAndAssemblyOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(name, assembly, request);
        assembly.setChromosomes(null);
        return injectAssemblyIntoChromosomes(page, assembly);
    }

    public Page<ChromosomeEntity> getChromosomesByAssemblyAccession(String accession, Pageable request) {
        Page<ChromosomeEntity> chromosomes = repository.findChromosomeEntitiesByAssemblyInsdcAccessionOrAssemblyRefseqOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(
                accession, accession, request);
        return stripAssembliesFromChromosomes(chromosomes);
    }

    public Page<ChromosomeEntity> getChromosomesByUcscName(String ucscName, Pageable request) {
        Page<ChromosomeEntity> page = repository.findChromosomeEntitiesByUcscNameOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(ucscName, request);
        return stripChromosomesAndScaffoldsFromAssembly(page);
    }

    public Page<ChromosomeEntity> getChromosomesByUcscNameAndAssemblyTaxid(
            String ucscName, long asmTaxid, Pageable request) {
        Page<ChromosomeEntity> page
                = repository.findChromosomeEntitiesByUcscNameAndAssembly_TaxidOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(ucscName, asmTaxid, request);
        return stripChromosomesAndScaffoldsFromAssembly(page);
    }

    public Page<ChromosomeEntity> getChromosomesByUcscNameAndAssembly(String ucscName, AssemblyEntity assembly,
                                                                      Pageable request) {
        Page<ChromosomeEntity> page
                = repository.findChromosomeEntitiesByUcscNameAndAssemblyOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(ucscName, assembly, request);
        assembly.setChromosomes(null);
        return injectAssemblyIntoChromosomes(page, assembly);
    }

    public Page<ChromosomeEntity> getChromosomesByEnaName(String enaName, Pageable request) {
        Page<ChromosomeEntity> page = repository.findChromosomeEntitiesByEnaSequenceNameOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(enaName, request);
        return stripChromosomesAndScaffoldsFromAssembly(page);
    }

    public Page<ChromosomeEntity> getChromosomesByEnaNameAndAssemblyTaxid(
            String enaName, long asmTaxid, Pageable request) {
        Page<ChromosomeEntity> page
                = repository.findChromosomeEntitiesByEnaSequenceNameAndAssembly_TaxidOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(enaName, asmTaxid, request);
        return stripChromosomesAndScaffoldsFromAssembly(page);
    }

    public Page<ChromosomeEntity> getChromosomesByEnaNameAndAssembly(
            String enaName, AssemblyEntity assembly, Pageable request) {
        Page<ChromosomeEntity> page
                = repository.findChromosomeEntitiesByEnaSequenceNameAndAssemblyOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(enaName, assembly, request);
        assembly.setChromosomes(null);
        return injectAssemblyIntoChromosomes(page, assembly);
    }

    public Page<ChromosomeEntity> getChromosomesByMD5Checksum(String md5Checksum, Pageable request) {
        Page<ChromosomeEntity> chrPage = repository.findChromosomeEntitiesByMd5checksumOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(md5Checksum, request);
        return stripChromosomesAndScaffoldsFromAssembly(chrPage);
    }

    public Page<ChromosomeEntity> searchChromosomeByName(String chromosomeName,
                                                         String namingConvention,
                                                         String assemblyAccession,
                                                         Pageable pageable) {
        Specification<ChromosomeEntity> spec = queryForSearchingByNameAndNamingConvention(chromosomeName, namingConvention);

        if (assemblyAccession != null && !assemblyAccession.isEmpty()) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("assembly").get("insdcAccession"), assemblyAccession));
        }
        Page<ChromosomeEntity> resultPage = repository.findAll(spec, pageable);
        return stripChromosomesAndScaffoldsFromAssembly(resultPage);
    }

    private Specification<ChromosomeEntity> queryForSearchingByNameAndNamingConvention(String chromosomeName, String namingConvention) {
        return (root, query, cb) -> {
            if (namingConvention == null || namingConvention.isEmpty()) {
                return cb.or(
                        cb.equal(root.get("genbankSequenceName"), chromosomeName),
                        cb.equal(root.get("enaSequenceName"), chromosomeName),
                        cb.equal(root.get("insdcAccession"), chromosomeName),
                        cb.equal(root.get("refseq"), chromosomeName),
                        cb.equal(root.get("ucscName"), chromosomeName)
                );
            } else {
                switch (namingConvention) {
                    case AUTHORITY_INSDC:
                        return cb.equal(root.get("insdcAccession"), chromosomeName);
                    case NAME_GENBANK_TYPE:
                        return cb.equal(root.get("genbankSequenceName"), chromosomeName);
                    case NAME_ENA_TYPE:
                        return cb.equal(root.get("enaSequenceName"), chromosomeName);
                    case AUTHORITY_REFSEQ:
                        return cb.equal(root.get("refseq"), chromosomeName);
                    case NAME_UCSC_TYPE:
                        return cb.equal(root.get("ucscName"), chromosomeName);
                    default:
                        throw new IllegalArgumentException("Unsupported naming convention: " + namingConvention);
                }
            }
        };
    }

    private Page<ChromosomeEntity> injectAssemblyIntoChromosomes(Page<ChromosomeEntity> page, AssemblyEntity assembly) {
        if (page != null && page.getTotalElements() > 0) {
            page.forEach(it -> it.setAssembly(assembly));
        }
        return page;
    }

    private Page<ChromosomeEntity> stripChromosomesAndScaffoldsFromAssembly(Page<ChromosomeEntity> page) {
        if (page != null && page.getTotalElements() > 0) {
            page.forEach(this::stripChromosomeFromAssembly);
        }
        return page;
    }

    private void stripChromosomeFromAssembly(ChromosomeEntity chromosome) {
        AssemblyEntity assembly = chromosome.getAssembly();
        if (assembly != null) {
            assembly.setChromosomes(null);
        }
    }

    private Page<ChromosomeEntity> stripAssembliesFromChromosomes(Page<ChromosomeEntity> page) {
        if (page != null && page.getTotalElements() > 0) {
            page.forEach(this::stripAssemblyFromChromosome);
        }
        return page;
    }

    private void stripAssemblyFromChromosome(ChromosomeEntity chromosome) {
        chromosome.setAssembly(null);
    }

    public void putChromosomeChecksumsByAccession(String accession, String md5, String trunc512) {
        Page<ChromosomeEntity> page = repository.findChromosomeEntitiesByInsdcAccessionOrRefseqOrderByInsdcAccessionDescAssembly_InsdcAccessionDesc(
                accession, accession, Pageable.unpaged());
        if (page.isEmpty()) {
            throw new IllegalArgumentException(
                    "No chromosomes corresponding to accession " + accession + " found in the database");
        }
        page.forEach(it -> {
            it.setMd5checksum(md5).setTrunc512checksum(trunc512);
            repository.save(it);
        });
    }

    public void insertChromosome(ChromosomeEntity entity) {
        // TODO check if entity already exists in db
        repository.save(entity);
    }

    public void deleteChromosome(ChromosomeEntity entity) {
        // TODO check if entity already exists in db
        repository.delete(entity);
    }

    public long countChromosomeEntitiesByInsdcAccession(String insdcAccession) {
        return repository.countChromosomeEntitiesByInsdcAccession(insdcAccession);
    }

    public long countChromosomeEntitiesByRefseq(String refseq) {
        return repository.countChromosomeEntitiesByRefseq(refseq);
    }

    public long countChromosomeEntitiesByAssemblyInsdcAccession(String asmInsdcAccession) {
        return repository.countChromosomeEntitiesByAssemblyInsdcAccession(asmInsdcAccession);
    }

    public long countChromosomeEntitiesByAssembly_Refseq(String asmRefseq) {
        return repository.countChromosomeEntitiesByAssembly_Refseq(asmRefseq);
    }

    public long countChromosomeEntitiesByNameAndAssembly_Taxid(String name, long asmTaxid) {
        return repository.countChromosomeEntitiesByGenbankSequenceNameAndAssembly_Taxid(name, asmTaxid);
    }

    public long countChromosomeEntitiesByUcscNameAndAssembly_Taxid(String ucscName, long asmTaxid) {
        return repository.countChromosomeEntitiesByUcscNameAndAssembly_Taxid(ucscName, asmTaxid);
    }

    public long countChromosomeEntitiesByEnaNameAndAssembly_Taxid(String enaName, long asmTaxid) {
        return repository.countChromosomeEntitiesByEnaSequenceNameAndAssembly_Taxid(enaName, asmTaxid);
    }

    public long countChromosomeEntitiesByNameAndAssembly(String name, AssemblyEntity assembly) {
        return repository.countChromosomeEntitiesByGenbankSequenceNameAndAssembly(name, assembly);
    }

    public long countChromosomeEntitiesByUcscNameAndAssembly(String ucscName, AssemblyEntity assembly) {
        return repository.countChromosomeEntitiesByUcscNameAndAssembly(ucscName, assembly);
    }

    public long countChromosomeEntitiesByEnaNameAndAssembly(String enaName, AssemblyEntity assembly) {
        return repository.countChromosomeEntitiesByEnaSequenceNameAndAssembly(enaName, assembly);
    }

    public long countChromosomeEntitiesByName(String name) {
        return repository.countChromosomeEntitiesByGenbankSequenceName(name);
    }

    public long countChromosomeEntitiesByAssemblyGenbankOrAssemblyRefseq(String insdcAccession, String refseq) {
        return repository.countChromosomeEntitiesByAssemblyInsdcAccessionOrAssemblyRefseq(insdcAccession, refseq);
    }

    public long countChromosomeEntitiesByUcscName(String ucscName) {
        return repository.countChromosomeEntitiesByUcscName(ucscName);
    }

    public long countChromosomeEntitiesByEnaName(String enaName) {
        return repository.countChromosomeEntitiesByEnaSequenceName(enaName);
    }

    public void insertAllChromosomes(List<ChromosomeEntity> chromosomeEntityList) {
        String sql = "INSERT INTO chromosome (assembly_insdc_accession,contig_type,ena_sequence_name," +
                "genbank_sequence_name,insdc_accession,md5checksum,refseq,seq_length,trunc512checksum,ucsc_name) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ChromosomeEntity chromosome = chromosomeEntityList.get(i);
                ps.setString(1, chromosome.getAssembly().getInsdcAccession());
                ps.setString(2, chromosome.getContigType().toString());
                ps.setString(3, chromosome.getEnaSequenceName());
                ps.setString(4, chromosome.getGenbankSequenceName());
                ps.setString(5, chromosome.getInsdcAccession());
                ps.setString(6, chromosome.getMd5checksum());
                ps.setString(7, chromosome.getRefseq());
                ps.setLong(8, chromosome.getSeqLength());
                ps.setString(9, chromosome.getTrunc512checksum());
                ps.setString(10, chromosome.getUcscName());
            }

            @Override
            public int getBatchSize() {
                return chromosomeEntityList.size();
            }
        });
    }

}
