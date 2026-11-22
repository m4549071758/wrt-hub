ALTER TABLE `push_targets` RENAME COLUMN "endpoint" TO "token";--> statement-breakpoint
DELETE FROM `push_targets`;