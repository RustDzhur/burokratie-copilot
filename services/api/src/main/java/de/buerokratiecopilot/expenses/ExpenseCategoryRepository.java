package de.buerokratiecopilot.expenses;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.expenses.domain.ExpenseCategory;

/**
 * The expense catalogue.
 *
 * <p>The catalogue is platform-wide, like the document taxonomy: if every tenant could rename
 * "Material" the word would stop meaning the same thing across the platform. Rules and folders are
 * therefore shared defaults; a tenant configures its own DATEV accounts later through the administrator
 * interface.</p>
 */
public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

    Optional<ExpenseCategory> findByCode(String code);

    boolean existsByCode(String code);

    List<ExpenseCategory> findByActiveTrueOrderBySortOrderAscCodeAsc();

    List<ExpenseCategory> findAllByOrderBySortOrderAscCodeAsc();

    List<ExpenseCategory> findByParentIdOrderBySortOrderAscCodeAsc(UUID parentId);
}
