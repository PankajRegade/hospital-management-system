package HMS.example.HospitalManagementSystem.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import HMS.example.HospitalManagementSystem.model.Patient;

public interface PatientRepository extends JpaRepository<Patient, Long> {
}