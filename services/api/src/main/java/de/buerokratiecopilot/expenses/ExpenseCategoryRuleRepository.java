package de.buerokratiecopilot.expenses;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.expenses.domain.ExpenseCategoryRule;

/** The rules that recognise an expense category. */
public interface ExpenseCategoryRuleRepository extends JpaRepository<ExpenseCategoryRule, UUID> {

    List<ExpenseCategoryRule> findByActiveTrueOrderByPriorityDesc();

    List<ExpenseCategoryRule> findByCategoryIdOrderByPriorityDescPatternAsc(UUID categoryId);

    List<ExpenseCategoryRule> findByCategoryIdAndActiveTrueOrderByPriorityDesc(UUID categoryId);
}
