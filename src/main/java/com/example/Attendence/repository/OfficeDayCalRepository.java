package com.example.Attendence.repository;

import com.example.Attendence.model.LocalSetting;
import com.example.Attendence.model.OfficeDayCal;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface OfficeDayCalRepository extends MongoRepository<OfficeDayCal, String> {
    Optional<OfficeDayCal> findByDate(String entryDate);
     int countByMonthAndYearAndStatus(String month, String year, String status);
     // count between dates
      int countByEntryDateBetweenAndStatus(LocalDate startDate, LocalDate endDate, String status);
}
