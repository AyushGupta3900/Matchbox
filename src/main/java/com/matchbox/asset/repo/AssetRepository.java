package com.matchbox.asset.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.matchbox.asset.domain.Asset;

public interface AssetRepository extends JpaRepository<Asset,Integer> {
    
}
